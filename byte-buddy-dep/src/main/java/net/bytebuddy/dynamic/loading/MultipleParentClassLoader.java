/*
 * Copyright 2014 - Present Rafael Winterhalter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bytebuddy.dynamic.loading;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.build.HashCodeAndEqualsPlugin;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.nullability.MaybeNull;
import net.bytebuddy.utility.nullability.UnknownNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * <p>
 * This {@link java.lang.ClassLoader} is capable of loading classes from multiple parents. This class loader
 * implicitly defines the bootstrap class loader to be its direct parent as it is required for all class loaders.
 * This can be useful when creating a type that inherits a super type and interfaces that are defined by different,
 * non-compatible class loaders.
 * </p>
 * <p>
 * <b>Note</b>: Instances of this class loader can have the same class loader as its parent multiple times,
 * either directly or indirectly by multiple parents sharing a common parent class loader. By definition,
 * this implies that the bootstrap class loader is {@code #(direct parents) + 1} times a parent of this class loader.
 * For the {@link java.lang.ClassLoader#getResources(java.lang.String)} method, this means that this class loader
 * might return the same url multiple times by representing the same class loader multiple times.
 * </p>
 * <p>
 * <b>Important</b>: This class loader does not support the location of packages from its multiple parents. This breaks
 * package equality when loading classes by either loading them directly via this class loader (e.g. by subclassing) or
 * by loading classes with child class loaders of this class loader.
 * </p>
 */
public class MultipleParentClassLoader extends InjectionClassLoader {

    /*
     * Register class loader as parallel capable if the current VM supports it.
     */
    static {
        doRegisterAsParallelCapable();
    }

    /**
     * Registers class loader as parallel capable if possible.
     */
    @SuppressFBWarnings(value = "DP_DO_INSIDE_DO_PRIVILEGED", justification = "Must be invoked from targeting class loader type.")
    private static void doRegisterAsParallelCapable() {
        try {
            Method method = ClassLoader.class.getDeclaredMethod("registerAsParallelCapable");
            method.setAccessible(true);
            method.invoke(null);
        } catch (Throwable ignored) {
            /* do nothing */
        }
    }

    /**
     * The parents of this class loader in their application order.
     */
    private final List<? extends ClassLoader> parents;

    /**
     * Creates a new class loader with multiple parents.
     *
     * @param parents The parents of this class loader in their application order. This list must not contain {@code null},
     *                i.e. the bootstrap class loader which is an implicit parent of any class loader.
     */
    public MultipleParentClassLoader(List<? extends ClassLoader> parents) {
        this(ClassLoadingStrategy.BOOTSTRAP_LOADER, parents);
    }

    /**
     * Creates a new class loader with multiple parents.
     *
     * @param parent  An explicit parent in compliance with the class loader API. This explicit parent should only be set if
     *                the current platform does not allow creating a class loader that extends the bootstrap loader.
     * @param parents The parents of this class loader in their application order. This list must not contain {@code null},
     *                i.e. the bootstrap class loader which is an implicit parent of any class loader.
     */
    public MultipleParentClassLoader(@MaybeNull ClassLoader parent, List<? extends ClassLoader> parents) {
        this(parent, parents, true);
    }

    /**
     * Creates a new class loader with multiple parents.
     *
     * @param parent  An explicit parent in compliance with the class loader API. This explicit parent should only be set if
     *                the current platform does not allow creating a class loader that extends the bootstrap loader.
     * @param parents The parents of this class loader in their application order. This list must not contain {@code null},
     *                i.e. the bootstrap class loader which is an implicit parent of any class loader.
     * @param sealed  {@code true} if the class loader is sealed for injection of additional classes.
     */
    public MultipleParentClassLoader(@MaybeNull ClassLoader parent, List<? extends ClassLoader> parents, boolean sealed) {
        super(parent, sealed);
        this.parents = parents;
    }

    /**
     * {@inheritDoc}
     */
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (ClassLoader parent : parents) {
            try {
                Class<?> type = parent.loadClass(name);
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            } catch (ClassNotFoundException ignored) {
                /* try next class loader */
            }
        }
        return super.loadClass(name, resolve);
    }

    /**
     * {@inheritDoc}
     */
    public URL getResource(String name) {
        for (ClassLoader parent : parents) {
            URL url = parent.getResource(name);
            if (url != null) {
                return url;
            }
        }
        return super.getResource(name);
    }

    /**
     * {@inheritDoc}
     */
    public Enumeration<URL> getResources(String name) throws IOException {
        List<Enumeration<URL>> enumerations = new ArrayList<Enumeration<URL>>(parents.size() + 1);
        for (ClassLoader parent : parents) {
            enumerations.add(parent.getResources(name));
        }
        enumerations.add(super.getResources(name));
        return new CompoundEnumeration(enumerations);
    }

    @Override
    protected Map<String, Class<?>> doDefineClasses(Map<String, byte[]> typeDefinitions) {
        Map<String, Class<?>> types = new HashMap<String, Class<?>>();
        for (Map.Entry<String, byte[]> entry : typeDefinitions.entrySet()) {
            types.put(entry.getKey(), defineClass(entry.getKey(), entry.getValue(), 0, entry.getValue().length));
        }
        return types;
    }

    /**
     * A compound URL enumeration.
     */
    protected static class CompoundEnumeration implements Enumeration<URL> {

        /**
         * Indicates the first index of a list.
         */
        private static final int FIRST = 0;

        /**
         * The remaining lists of enumerations.
         */
        private final List<Enumeration<URL>> enumerations;

        /**
         * The currently represented enumeration or {@code null} if no such enumeration is currently selected.
         */
        @UnknownNull
        private Enumeration<URL> current;

        /**
         * Creates a compound enumeration.
         *
         * @param enumerations The enumerations to represent.
         */
        protected CompoundEnumeration(List<Enumeration<URL>> enumerations) {
            this.enumerations = enumerations;
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasMoreElements() {
            if (current != null && current.hasMoreElements()) {
                return true;
            } else if (!enumerations.isEmpty()) {
                current = enumerations.remove(FIRST);
                return hasMoreElements();
            } else {
                return false;
            }
        }

        /**
         * {@inheritDoc}
         */
        @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "Null reference is avoided by element check.")
        public URL nextElement() {
            if (hasMoreElements()) {
                return current.nextElement();
            } else {
                throw new NoSuchElementException();
            }
        }
    }

    /**
     * A builder to collect class loader and that creates a
     * {@link net.bytebuddy.dynamic.loading.MultipleParentClassLoader} only if multiple or no
     * {@link java.lang.ClassLoader}s are found in the process. If exactly a single class loader is found,
     * this class loader is returned. All class loaders are applied in their collection order with the exception
     * of the bootstrap class loader which is represented by {@code null} and which is an implicit parent of any
     * class loader.
     */
    @HashCodeAndEqualsPlugin.Enhance
    public static class Builder {

        /**
         * Indicates the first index of a list.
         */
        private static final int ONLY = 0;

        /**
         * {@code true} if the created class loader is sealed.
         */
        private final boolean sealed;

        /**
         * The class loaders that were collected.
         */
        private final List<? extends ClassLoader> classLoaders;

        /**
         * Creates a new builder without any class loaders.
         */
        public Builder() {
            this(true);
        }

        /**
         * Creates a new builder without any class loaders.
         *
         * @param sealed {@code true} if the created class loader is sealed.
         */
        public Builder(boolean sealed) {
            this(Collections.<ClassLoader>emptyList(), sealed);
        }

        /**
         * Creates a new builder.
         *
         * @param classLoaders The class loaders that were collected until now.
         * @param sealed       {@code true} if the created class loader is sealed.
         */
        private Builder(List<? extends ClassLoader> classLoaders, boolean sealed) {
            this.classLoaders = classLoaders;
            this.sealed = sealed;
        }

        /**
         * Appends the class loaders of the given types. The bootstrap class loader is implicitly skipped as
         * it is an implicit parent of any class loader.
         *
         * @param type The types of which to collect the class loaders.
         * @return A new builder instance with the additional class loaders of the provided types if they were not
         * yet collected.
         */
        public Builder append(Class<?>... type) {
            return append(Arrays.asList(type));
        }

        /**
         * Appends the class loaders of the given types if those class loaders were not yet collected. The bootstrap class
         * loader is implicitly skipped as it is an implicit parent of any class loader.
         *
         * @param types The types of which to collect the class loaders.
         * @return A new builder instance with the additional class loaders.
         */
        public Builder append(Collection<? extends Class<?>> types) {
            List<ClassLoader> classLoaders = new ArrayList<ClassLoader>(types.size());
            for (Class<?> type : types) {
                classLoaders.add(type.getClassLoader());
            }
            return append(classLoaders);
        }

        /**
         * Appends the given class loaders if they were not yet collected. The bootstrap class loader is implicitly
         * skipped as it is an implicit parent of any class loader.
         *
         * @param classLoader The class loaders to be collected.
         * @return A new builder instance with the additional class loaders.
         */
        public Builder append(ClassLoader... classLoader) {
            return append(Arrays.asList(classLoader));
        }

        /**
         * Appends the given class loaders if they were not yet appended. The bootstrap class loader is never appended as
         * it is an implicit parent of any class loader.
         *
         * @param classLoaders The class loaders to collected.
         * @return A new builder instance with the additional class loaders.
         */
        public Builder append(List<? extends ClassLoader> classLoaders) {
            List<ClassLoader> filtered = new ArrayList<ClassLoader>(this.classLoaders.size() + classLoaders.size());
            filtered.addAll(this.classLoaders);
            Set<ClassLoader> registered = new HashSet<ClassLoader>(this.classLoaders);
            for (ClassLoader classLoader : classLoaders) {
                if (classLoader != null && registered.add(classLoader)) {
                    filtered.add(classLoader);
                }
            }
            return new Builder(filtered, sealed);
        }

        /**
         * Appends the class loaders of the given types but filters any duplicates within the hierarchy of class loaders.
         * The bootstrap class loader is implicitly skipped as it is an implicit parent of any class loader. Class loaders
         * are prepended to the list of class loaders.
         *
         * @param type The types of which to collect the class loaders.
         * @return A new builder instance with the additional class loaders of the provided types if they were not
         * yet collected.
         */
        public Builder appendMostSpecific(Class<?>... type) {
            return appendMostSpecific(Arrays.asList(type));
        }

        /**
         * Appends the class loaders of the given types but filters any duplicates within the hierarchy of class loaders.
         * The bootstrap class loader is implicitly skipped as it is an implicit parent of any class loader. Class loaders
         * are prepended to the list of class loaders.
         *
         * @param types The types of which to collect the class loaders.
         * @return A new builder instance with the additional class loaders.
         */
        public Builder appendMostSpecific(Collection<? extends Class<?>> types) {
            List<ClassLoader> classLoaders = new ArrayList<ClassLoader>(types.size());
            for (Class<?> type : types) {
                classLoaders.add(type.getClassLoader());
            }
            return appendMostSpecific(classLoaders);
        }

        /**
         * Appends the given class loaders but removes any class loaders that are the parent of any previously registered class loader.
         * The bootstrap class loader is implicitly skipped as it is an implicit parent of any class loader.
         *
         * @param classLoader The class loaders to be collected.
         * @return A new builder instance with the additional class loaders.
         */
        public Builder appendMostSpecific(ClassLoader... classLoader) {
            return appendMostSpecific(Arrays.asList(classLoader));
        }

        /**
         * Appends the given class loaders but removes any class loaders that are the parent of any previously registered class loader.
         * The bootstrap class loader is implicitly skipped as it is an implicit parent of any class loader.
         *
         * @param classLoaders The class loaders to collected.
         * @return A new builder instance with the additional class loaders.
         */
        public Builder appendMostSpecific(List<? extends ClassLoader> classLoaders) {
            List<ClassLoader> filtered = new ArrayList<ClassLoader>(this.classLoaders.size() + classLoaders.size());
            filtered.addAll(this.classLoaders);
            consideration:
            for (ClassLoader classLoader : classLoaders) {
                if (classLoader == null) {
                    continue;
                }
                ClassLoader candidate = classLoader;
                do {
                    Iterator<ClassLoader> iterator = filtered.iterator();
                    while (iterator.hasNext()) {
                        ClassLoader previous = iterator.next();
                        if (previous.equals(candidate)) {
                            iterator.remove();
                        }
                    }
                } while ((candidate = candidate.getParent()) != null);
                for (ClassLoader previous : filtered) {
                    do {
                        if (previous.equals(classLoader)) {
                            continue consideration;
                        }
                    } while ((previous = previous.getParent()) != null);
                }
                filtered.add(classLoader);
            }
            return new Builder(filtered, sealed);
        }

        /**
         * Only retains all class loaders that match the given matcher.
         *
         * @param matcher The matcher to be used for filtering.
         * @return A builder that does not longer consider any appended class loaders that matched the provided matcher.
         */
        public Builder filter(ElementMatcher<? super ClassLoader> matcher) {
            List<ClassLoader> classLoaders = new ArrayList<ClassLoader>(this.classLoaders.size());
            for (ClassLoader classLoader : this.classLoaders) {
                if (matcher.matches(classLoader)) {
                    classLoaders.add(classLoader);
                }
            }
            return new Builder(classLoaders, sealed);
        }

        /**
         * <p>
         * Returns the only class loader that was appended if exactly one class loader was appended or a multiple parent class loader as
         * a parent of all supplied class loader and with the bootstrap class loader as an implicit parent. If no class loader was appended,
         * a new class loader is created that declares no parents. If a class loader is created, its explicit parent is set to be the
         * bootstrap class loader.
         * </p>
         * <p>
         * <b>Important</b>: Byte Buddy does not provide any access control for the creation of the class loader. It is the responsibility
         * of the user of this builder to provide such privileges.
         * </p>
         *
         * @return A suitable class loader.
         */
        @SuppressFBWarnings(value = "DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED", justification = "Assuring privilege is explicit user responsibility.")
        public ClassLoader build() {
            return classLoaders.size() == 1
                    ? classLoaders.get(ONLY)
                    : new MultipleParentClassLoader(classLoaders);
        }

        /**
         * <p>
         * Returns the only class loader that was appended if exactly one class loader was appended or a multiple parent class loader as
         * a parent of all supplied class loader and with the bootstrap class loader as an implicit parent. If no class loader was appended,
         * or if only the supplied parent to this method was included, this class loader is returned,
         * </p>
         * <p>
         * <b>Important</b>: Byte Buddy does not provide any access control for the creation of the class loader. It is the responsibility
         * of the user of this builder to provide such privileges.
         * </p>
         *
         * @param parent The class loader's contractual parent which is accessible via {@link ClassLoader#getParent()}. If this class loader
         *               is also included in the appended class loaders, it is not
         * @return A suitable class loader.
         */
        public ClassLoader build(ClassLoader parent) {
            return classLoaders.isEmpty() || classLoaders.size() == 1 && classLoaders.contains(parent)
                    ? parent
                    : filter(not(is(parent))).doBuild(parent);
        }

        /**
         * Creates a multiple parent class loader with an explicit parent.
         *
         * @param parent The explicit parent class loader.
         * @return A multiple parent class loader that includes all collected class loaders and the explicit parent.
         */
        @SuppressFBWarnings(value = "DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED", justification = "Assuring privilege is explicit user responsibility.")
        private ClassLoader doBuild(@MaybeNull ClassLoader parent) {
            return new MultipleParentClassLoader(parent, classLoaders, sealed);
        }
    }
}
