package net.bytebuddy.agent.builder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.modifier.*;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.ExceptionMethod;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.LoadedTypeInitializer;
import net.bytebuddy.implementation.auxiliary.AuxiliaryType;
import net.bytebuddy.implementation.bytecode.*;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.implementation.bytecode.collection.ArrayFactory;
import net.bytebuddy.implementation.bytecode.constant.ClassConstant;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.implementation.bytecode.constant.NullConstant;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.implementation.bytecode.member.*;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaInstance;
import net.bytebuddy.utility.JavaType;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * <p>
 * An agent builder provides a convenience API for defining a
 * <a href="http://docs.oracle.com/javase/6/docs/api/java/lang/instrument/package-summary.html">Java agent</a>. By default,
 * this transformation is applied by rebasing the type if not specified otherwise by setting a
 * {@link TypeStrategy}.
 * </p>
 * <p>
 * When defining several {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s, the agent builder always
 * applies the transformers that were supplied with the last applicable matcher. Therefore, more general transformers
 * should be defined first.
 * </p>
 */
public interface AgentBuilder {

    /**
     * <p>
     * Matches a type being loaded in order to apply the supplied
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s before loading this type.
     * </p>
     * <p>
     * <b>Note</b>: When applying a matcher, regard the performance implications by
     * {@link AgentBuilder#ignore(ElementMatcher)}. The former matcher is applied first such that it makes sense
     * to ignore name spaces that are irrelevant to instrumentation. If possible, it is also recommended, to
     * exclude class loaders such as for example the bootstrap class loader.
     * </p>
     *
     * @param matcher A matcher that decides if the entailed
     *                {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s should be applied for a type that
     *                is being loaded.
     * @return A definable that represents this agent builder which allows for the definition of one or several
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s to be applied when the given {@code matcher}
     * indicates a match.
     */
    Identified type(RawMatcher matcher);

    /**
     * <p>
     * Matches a type being loaded in order to apply the supplied
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s before loading this type.
     * </p>
     * <p>
     * <b>Note</b>: When applying a matcher, regard the performance implications by
     * {@link AgentBuilder#ignore(ElementMatcher)}. The former matcher is applied first such that it makes sense
     * to ignore name spaces that are irrelevant to instrumentation. If possible, it is also recommended, to exclude
     * class loaders such as for example the bootstrap class loader by using
     * {@link AgentBuilder#type(ElementMatcher, ElementMatcher)} instead.
     * </p>
     *
     * @param typeMatcher An {@link net.bytebuddy.matcher.ElementMatcher} that is applied on the type being loaded that
     *                    decides if the entailed {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s should
     *                    be applied for that type.
     * @return A definable that represents this agent builder which allows for the definition of one or several
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s to be applied when the given {@code typeMatcher}
     * indicates a match.
     */
    Identified type(ElementMatcher<? super TypeDescription> typeMatcher);

    /**
     * <p>
     * Matches a type being loaded in order to apply the supplied
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s before loading this type.
     * </p>
     * <p>
     * <b>Note</b>: When applying a matcher, regard the performance implications by
     * {@link AgentBuilder#ignore(ElementMatcher)}. The former matcher is applied first such that it makes sense
     * to ignore name spaces that are irrelevant to instrumentation.
     * </p>
     *
     * @param typeMatcher        An {@link net.bytebuddy.matcher.ElementMatcher} that is applied on the type being
     *                           loaded that decides if the entailed
     *                           {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s should be applied for
     *                           that type.
     * @param classLoaderMatcher An {@link net.bytebuddy.matcher.ElementMatcher} that is applied to the
     *                           {@link java.lang.ClassLoader} that is loading the type being loaded. This matcher
     *                           is always applied first where the type matcher is not applied in case that this
     *                           matcher does not indicate a match.
     * @return A definable that represents this agent builder which allows for the definition of one or several
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s to be applied when both the given
     * {@code typeMatcher} and {@code classLoaderMatcher} indicate a match.
     */
    Identified type(ElementMatcher<? super TypeDescription> typeMatcher, ElementMatcher<? super ClassLoader> classLoaderMatcher);

    /**
     * Defines the given {@link net.bytebuddy.ByteBuddy} instance to be used by the created agent.
     *
     * @param byteBuddy The Byte Buddy instance to be used.
     * @return A new instance of this agent builder which makes use of the given {@code byteBuddy} instance.
     */
    AgentBuilder with(ByteBuddy byteBuddy);

    /**
     * Defines the given {@link net.bytebuddy.agent.builder.AgentBuilder.Listener} to be notified by the created agent.
     * The given listener is notified after any other listener that is already registered. If a listener is registered
     * twice, it is also notified twice.
     *
     * @param listener The listener to be notified.
     * @return A new instance of this agent builder which creates an agent that informs the given listener about
     * events.
     */
    AgentBuilder with(Listener listener);

    /**
     * Defines the use of the given binary locator for locating binary data to given class names.
     *
     * @param binaryLocator The binary locator to use.
     * @return A new instance of this agent builder which uses the given binary locator for looking up class files.
     */
    AgentBuilder with(BinaryLocator binaryLocator);

    /**
     * Defines the use of the given definition handler that determines if a type should be rebased or redefined.
     *
     * @param typeStrategy The definition handler to use.
     * @return A new instance of this agent builder which uses the given definition handler.
     */
    AgentBuilder with(TypeStrategy typeStrategy);

    /**
     * Defines classes to be loaded using the given access control context.
     *
     * @param accessControlContext The access control context to be used for loading classes.
     * @return A new instance of this agent builder which uses the given access control context for class loading.
     */
    AgentBuilder with(AccessControlContext accessControlContext);

    /**
     * Defines a given initialization strategy to be applied to generated types. An initialization strategy is responsible
     * for setting up a type after it was loaded. This initialization must be performed after the transformation because
     * a Java agent is only invoked before loading a type. By default, the initialization logic is added to a class's type
     * initializer which queries a global object for any objects that are to be injected into the generated type.
     *
     * @param initializationStrategy The initialization strategy to use.
     * @return A new instance of this agent builder that applies the given initialization strategy.
     */
    AgentBuilder with(InitializationStrategy initializationStrategy);

    /**
     * Specifies a strategy for modifying existing types.
     *
     * @param redefinitionStrategy The redefinition strategy to apply.
     * @return A new instance of this agent builder that applies the given redefinition strategy.
     */
    AgentBuilder with(RedefinitionStrategy redefinitionStrategy);

    /**
     * <p>
     * Enables or disables management of the JVM's {@code LambdaMetafactory} which is responsible for creating classes that
     * implement lambda expressions. Without this feature enabled, classes that are represented by lambda expressions are
     * not instrumented by the JVM such that Java agents have no effect on them when a lambda expression's class is loaded
     * for the first time.
     * </p>
     * <p>
     * When activating this feature, Byte Buddy instruments the {@code LambdaMetafactory} and takes over the responsibility
     * of creating classes that represent lambda expressions. In doing so, Byte Buddy has the opportunity to apply the built
     * class file transformer. If the current VM does not support lambda expressions, activating this feature has no effect.
     * </p>
     * <p>
     * <b>Important</b>: If this feature is active, it is important to release the built class file transformer when
     * deactivating it. Normally, it is sufficient to call {@link Instrumentation#removeTransformer(ClassFileTransformer)}.
     * When this feature is enabled, it is however also required to invoke
     * {@link LambdaInstrumentationStrategy#release(ClassFileTransformer, Instrumentation)}. Otherwise, the executing VMs class
     * loader retains a reference to the class file transformer what can cause a memory leak.
     * </p>
     *
     * @param lambdaInstrumentationStrategy {@code true} if this feature should be enabled.
     * @return A new instance of this agent builder where this feature is explicitly enabled or disabled.
     */
    AgentBuilder with(LambdaInstrumentationStrategy lambdaInstrumentationStrategy);

    /**
     * Enables class injection of auxiliary classes into the bootstrap class loader.
     *
     * @param folder          The folder in which jar files of the injected classes are to be stored.
     * @param instrumentation The instrumentation instance that is used for appending jar files to the
     *                        bootstrap class path.
     * @return An agent builder with bootstrap class loader class injection enabled.
     */
    AgentBuilder enableBootstrapInjection(File folder, Instrumentation instrumentation);

    /**
     * Enables the use of the given native method prefix for instrumented methods. Note that this prefix is also
     * applied when preserving non-native methods. The use of this prefix is also registered when installing the
     * final agent with an {@link java.lang.instrument.Instrumentation}.
     *
     * @param prefix The prefix to be used.
     * @return A new instance of this agent builder which uses the given native method prefix.
     */
    AgentBuilder enableNativeMethodPrefix(String prefix);

    /**
     * Disables the use of a native method prefix for instrumented methods.
     *
     * @return A new instance of this agent builder which does not use a native method prefix.
     */
    AgentBuilder disableNativeMethodPrefix();

    /**
     * Disables injection of auxiliary classes into the bootstrap class path.
     *
     * @return A new instance of this agent builder which does not apply bootstrap class loader injection.
     */
    AgentBuilder disableBootstrapInjection();

    /**
     * <p>
     * Excludes any type that is matched by the provided matcher from instrumentation. By default, Byte Buddy does not
     * instrument synthetic types.
     * </p>
     * <p>
     * <b>Note</b>: For performance reasons, it is recommended to always include a matcher that excludes as many namespaces
     * as possible. Byte Buddy can determine a type's name without parsing its class file and can therefore discard such
     * types prematurely. When a different property of a type - such as for example its modifiers or its annotations is
     * accessed - Byte Buddy parses the class file lazily in order to allow for such a matching. Therefore, any exclusion
     * of a name should always be done as a first step and even if it does not influence the selection of what types are
     * matched. Without changing this property, the class file of every type is being parsed!
     * </p>
     *
     * @param ignoredTypes A matcher that identifies types that should not be instrumented.
     * @return A new instance of this agent builder that ignores all types that are matched by the provided matcher.
     * All previous matchers for ignored types are discarded.
     */
    AgentBuilder ignore(ElementMatcher<? super TypeDescription> ignoredTypes);

    /**
     * Creates a {@link java.lang.instrument.ClassFileTransformer} that implements the configuration of this
     * agent builder.
     *
     * @return A class file transformer that implements the configuration of this agent builder.
     */
    ClassFileTransformer makeRaw();

    /**
     * Creates and installs a {@link java.lang.instrument.ClassFileTransformer} that implements the configuration of
     * this agent builder with a given {@link java.lang.instrument.Instrumentation}. If retransformation is enabled,
     * the installation also causes all loaded types to be retransformed.
     *
     * @param instrumentation The instrumentation on which this agent builder's configuration is to be installed.
     * @return The installed class file transformer.
     */
    ClassFileTransformer installOn(Instrumentation instrumentation);

    /**
     * Creates and installs a {@link java.lang.instrument.ClassFileTransformer} that implements the configuration of
     * this agent builder with the Byte Buddy-agent which must be installed prior to calling this method.
     *
     * @return The installed class file transformer.
     */
    ClassFileTransformer installOnByteBuddyAgent();

    /**
     * Describes an {@link net.bytebuddy.agent.builder.AgentBuilder} which was handed a matcher for identifying
     * types to instrumented in order to supply one or several
     * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s.
     */
    interface Identified {

        /**
         * Applies the given transformer for the already supplied matcher.
         *
         * @param transformer The transformer to apply.
         * @return This agent builder with the transformer being applied when the previously supplied matcher
         * identified a type for instrumentation which also allows for the registration of subsequent transformers.
         */
        Extendable transform(Transformer transformer);

        /**
         * This interface is used to allow for optionally providing several
         * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer} to applied when a matcher identifies a type
         * to be instrumented. Any subsequent transformers are applied in the order they are registered.
         */
        interface Extendable extends AgentBuilder, Identified {
            /* this is merely a unionizing interface that does not declare methods */
        }
    }

    /**
     * A matcher that allows to determine if a {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}
     * should be applied during the execution of a {@link java.lang.instrument.ClassFileTransformer} that was
     * generated by an {@link net.bytebuddy.agent.builder.AgentBuilder}.
     */
    interface RawMatcher {

        /**
         * Decides if the given {@code typeDescription} should be instrumented with the entailed
         * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s.
         *
         * @param typeDescription     A description of the type to be instrumented.
         * @param classLoader         The class loader of the instrumented type. Might be {@code null} if this class
         *                            loader represents the bootstrap class loader.
         * @param classBeingRedefined The class being redefined which is only not {@code null} if a retransformation
         *                            is applied.
         * @param protectionDomain    The protection domain of the type being transformed.
         * @return {@code true} if the entailed {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s should
         * be applied for the given {@code typeDescription}.
         */
        boolean matches(TypeDescription typeDescription,
                        ClassLoader classLoader,
                        Class<?> classBeingRedefined,
                        ProtectionDomain protectionDomain);

        /**
         * A raw matcher implementation that checks a {@link TypeDescription}
         * and its {@link java.lang.ClassLoader} against two suitable matchers in order to determine if the matched
         * type should be instrumented.
         */
        class ForElementMatcherPair implements RawMatcher {

            /**
             * The type matcher to apply to a {@link TypeDescription}.
             */
            private final ElementMatcher<? super TypeDescription> typeMatcher;

            /**
             * The class loader to apply to a {@link java.lang.ClassLoader}.
             */
            private final ElementMatcher<? super ClassLoader> classLoaderMatcher;

            /**
             * Creates a new {@link net.bytebuddy.agent.builder.AgentBuilder.RawMatcher} that only matches the
             * supplied {@link TypeDescription} and its {@link java.lang.ClassLoader} against two matcher in order
             * to decided if an instrumentation should be conducted.
             *
             * @param typeMatcher        The type matcher to apply to a
             *                           {@link TypeDescription}.
             * @param classLoaderMatcher The class loader to apply to a {@link java.lang.ClassLoader}.
             */
            public ForElementMatcherPair(ElementMatcher<? super TypeDescription> typeMatcher,
                                         ElementMatcher<? super ClassLoader> classLoaderMatcher) {
                this.typeMatcher = typeMatcher;
                this.classLoaderMatcher = classLoaderMatcher;
            }

            @Override
            public boolean matches(TypeDescription typeDescription,
                                   ClassLoader classLoader,
                                   Class<?> classBeingRedefined,
                                   ProtectionDomain protectionDomain) {
                return classLoaderMatcher.matches(classLoader) && typeMatcher.matches(typeDescription);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && classLoaderMatcher.equals(((ForElementMatcherPair) other).classLoaderMatcher)
                        && typeMatcher.equals(((ForElementMatcherPair) other).typeMatcher);
            }

            @Override
            public int hashCode() {
                int result = typeMatcher.hashCode();
                result = 31 * result + classLoaderMatcher.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "AgentBuilder.RawMatcher.ForElementMatcherPair{" +
                        "typeMatcher=" + typeMatcher +
                        ", classLoaderMatcher=" + classLoaderMatcher +
                        '}';
            }
        }
    }

    /**
     * A type strategy is responsible for creating a type builder for a type that is being instrumented.
     */
    interface TypeStrategy {

        /**
         * Creates a type builder for a given type.
         *
         * @param typeDescription       The type being instrumented.
         * @param byteBuddy             The Byte Buddy configuration.
         * @param classFileLocator      The class file locator to use.
         * @param methodNameTransformer The method name transformer to use.
         * @return A type builder for the given arguments.
         */
        DynamicType.Builder<?> builder(TypeDescription typeDescription,
                                       ByteBuddy byteBuddy,
                                       ClassFileLocator classFileLocator,
                                       MethodNameTransformer methodNameTransformer);

        /**
         * Default implementations of type strategies.
         */
        enum Default implements TypeStrategy {

            /**
             * A definition handler that performs a rebasing for all types.
             */
            REBASE {
                @Override
                public DynamicType.Builder<?> builder(TypeDescription typeDescription,
                                                      ByteBuddy byteBuddy,
                                                      ClassFileLocator classFileLocator,
                                                      MethodNameTransformer methodNameTransformer) {
                    return byteBuddy.rebase(typeDescription, classFileLocator, methodNameTransformer);
                }
            },

            /**
             * A definition handler that performs a redefinition for all types.
             */
            REDEFINE {
                @Override
                public DynamicType.Builder<?> builder(TypeDescription typeDescription,
                                                      ByteBuddy byteBuddy,
                                                      ClassFileLocator classFileLocator,
                                                      MethodNameTransformer methodNameTransformer) {
                    return byteBuddy.redefine(typeDescription, classFileLocator);
                }
            };

            @Override
            public String toString() {
                return "AgentBuilder.TypeStrategy.Default." + name();
            }
        }
    }

    /**
     * A transformer allows to apply modifications to a {@link net.bytebuddy.dynamic.DynamicType}. Such a modification
     * is then applied to any instrumented type that was matched by the preceding matcher.
     */
    interface Transformer {

        /**
         * Allows for a transformation of a {@link net.bytebuddy.dynamic.DynamicType.Builder}.
         *
         * @param builder         The dynamic builder to transform.
         * @param typeDescription The description of the type currently being instrumented.
         * @return A transformed version of the supplied {@code builder}.
         */
        DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription);

        /**
         * A no-op implementation of a {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer} that does
         * not modify the supplied dynamic type.
         */
        enum NoOp implements Transformer {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
                return builder;
            }

            @Override
            public String toString() {
                return "AgentBuilder.Transformer.NoOp." + name();
            }
        }

        /**
         * A compound transformer that allows to group several
         * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s as a single transformer.
         */
        class Compound implements Transformer {

            /**
             * The transformers to apply in their application order.
             */
            private final Transformer[] transformer;

            /**
             * Creates a new compound transformer.
             *
             * @param transformer The transformers to apply in their application order.
             */
            public Compound(Transformer... transformer) {
                this.transformer = transformer;
            }

            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
                for (Transformer transformer : this.transformer) {
                    builder = transformer.transform(builder, typeDescription);
                }
                return builder;
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && Arrays.equals(transformer, ((Compound) other).transformer);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(transformer);
            }

            @Override
            public String toString() {
                return "AgentBuilder.Transformer.Compound{" +
                        "transformer=" + Arrays.toString(transformer) +
                        '}';
            }
        }
    }

    /**
     * A binary locator allows to specify how binary data is located by an
     * {@link net.bytebuddy.agent.builder.AgentBuilder}.
     */
    interface BinaryLocator {

        /**
         * Creates a class file locator for the given class loader.
         *
         * @param classLoader The class loader for which a class file locator should be created.
         *                    Can be {@code null} to represent the bootstrap class loader.
         * @return An appropriate class file locator.
         */
        ClassFileLocator classFileLocator(ClassLoader classLoader);

        /**
         * Creates a type pool for a given class file locator.
         *
         * @param classFileLocator The class file locator to use.
         * @param classLoader      The class loader for which the class file locator was created.
         * @return A type pool for the supplied class file locator.
         */
        TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader);

        /**
         * A default implementation of a {@link net.bytebuddy.agent.builder.AgentBuilder.BinaryLocator} that
         * is using a {@link net.bytebuddy.pool.TypePool.Default} with a
         * {@link net.bytebuddy.pool.TypePool.CacheProvider.Simple} and a
         * {@link net.bytebuddy.dynamic.ClassFileLocator.ForClassLoader}.
         */
        enum Default implements BinaryLocator {

            /**
             * A binary locator that parses the code segment of each method for extracting information about parameter
             * names even if they are not explicitly included in a class file.
             *
             * @see net.bytebuddy.pool.TypePool.Default.ReaderMode#EXTENDED
             */
            EXTENDED(TypePool.Default.ReaderMode.EXTENDED),

            /**
             * A binary locator that skips the code segment of each method and does therefore not extract information
             * about parameter names. Parameter names are still included if they are explicitly included in a class file.
             *
             * @see net.bytebuddy.pool.TypePool.Default.ReaderMode#FAST
             */
            FAST(TypePool.Default.ReaderMode.FAST);

            /**
             * The reader mode to apply by this binary locator.
             */
            private final TypePool.Default.ReaderMode readerMode;

            /**
             * Creates a new binary locator.
             *
             * @param readerMode The reader mode to apply by this binary locator.
             */
            Default(TypePool.Default.ReaderMode readerMode) {
                this.readerMode = readerMode;
            }

            @Override
            public ClassFileLocator classFileLocator(ClassLoader classLoader) {
                return ClassFileLocator.ForClassLoader.of(classLoader);
            }

            @Override
            public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
                return new TypePool.LazyFacade(TypePool.Default.Precomputed.withObjectType(new TypePool.CacheProvider.Simple(), classFileLocator, readerMode));
            }

            @Override
            public String toString() {
                return "AgentBuilder.BinaryLocator.Default." + name();
            }
        }

        /**
         * <p>
         * A binary locator that loads referenced classes instead of describing unloaded versions.
         * </p>
         * <p>
         * <b>Important</b>: It is important to never query this binary locator for the currently instrumented type as this will yield a class
         * loading circularity which aborts any instrumentation with an error.
         * </p>
         * <p>
         * <b>Warning</b>: Warning, this binary locator <i>cannot be used for applying a redefinition</i> as it works on loaded classes only and
         * is agnostic of any way to locate a class file.
         * </p>
         */
        enum ClassLoading implements BinaryLocator {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public ClassFileLocator classFileLocator(ClassLoader classLoader) {
                return ClassFileLocator.NoOp.INSTANCE;
            }

            @Override
            public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
                return new TypePool.LazyFacade(TypePool.Default.ClassLoading.of(classFileLocator, classLoader));
            }

            @Override
            public String toString() {
                return "AgentBuilder.BinaryLocator.ClassLoading." + name();
            }
        }
    }

    /**
     * A listener that is informed about events that occur during an instrumentation process.
     */
    interface Listener {

        /**
         * Invoked right before a successful transformation is applied.
         *
         * @param typeDescription The type that is being transformed.
         * @param dynamicType     The dynamic type that was created.
         */
        void onTransformation(TypeDescription typeDescription, DynamicType dynamicType);

        /**
         * Invoked when a type is not transformed but ignored.
         *
         * @param typeDescription The type being ignored.
         */
        void onIgnored(TypeDescription typeDescription);

        /**
         * Invoked when an error has occurred.
         *
         * @param typeName  The binary name of the instrumented type.
         * @param throwable The occurred error.
         */
        void onError(String typeName, Throwable throwable);

        /**
         * Invoked after a class was attempted to be loaded, independently of its treatment.
         *
         * @param typeName The binary name of the instrumented type.
         */
        void onComplete(String typeName);

        /**
         * A no-op implementation of a {@link net.bytebuddy.agent.builder.AgentBuilder.Listener}.
         */
        enum NoOp implements Listener {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public void onTransformation(TypeDescription typeDescription, DynamicType dynamicType) {
                /* do nothing */
            }

            @Override
            public void onIgnored(TypeDescription typeDescription) {
                /* do nothing */
            }

            @Override
            public void onError(String typeName, Throwable throwable) {
                /* do nothing */
            }

            @Override
            public void onComplete(String typeName) {
                /* do nothing */
            }

            @Override
            public String toString() {
                return "AgentBuilder.Listener.NoOp." + name();
            }
        }

        /**
         * A listener that writes events to a {@link PrintStream}. This listener prints a line per event, including the event type and
         * the name of the type in question.
         */
        class StreamWriting implements Listener {

            /**
             * The prefix that is appended to all written messages.
             */
            protected static final String PREFIX = "[Byte Buddy]";

            /**
             * The print stream written to.
             */
            private final PrintStream printStream;

            /**
             * Creates a new stream writing listener.
             *
             * @param printStream The print stream written to.
             */
            public StreamWriting(PrintStream printStream) {
                this.printStream = printStream;
            }

            /**
             * Creates a new stream writing listener that writes to {@link System#out}.
             *
             * @return A listener writing events to the standard output stream.
             */
            public static Listener toSystemOut() {
                return new StreamWriting(System.out);
            }

            @Override
            public void onTransformation(TypeDescription typeDescription, DynamicType dynamicType) {
                printStream.println(PREFIX + " TRANSFORM " + typeDescription.getName());
            }

            @Override
            public void onIgnored(TypeDescription typeDescription) {
                printStream.println(PREFIX + " IGNORE " + typeDescription.getName());
            }

            @Override
            public void onError(String typeName, Throwable throwable) {
                printStream.println(PREFIX + " ERROR " + typeName);
                throwable.printStackTrace(printStream);
            }

            @Override
            public void onComplete(String typeName) {
                printStream.println(PREFIX + " COMPLETE " + typeName);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && printStream.equals(((StreamWriting) other).printStream);
            }

            @Override
            public int hashCode() {
                return printStream.hashCode();
            }

            @Override
            public String toString() {
                return "AgentBuilder.Listener.StreamWriting{" +
                        "printStream=" + printStream +
                        '}';
            }
        }

        /**
         * A compound listener that allows to group several listeners in one instance.
         */
        class Compound implements Listener {

            /**
             * The listeners that are represented by this compound listener in their application order.
             */
            private final List<? extends Listener> listeners;

            /**
             * Creates a new compound listener.
             *
             * @param listener The listeners to apply in their application order.
             */
            public Compound(Listener... listener) {
                this(Arrays.asList(listener));
            }

            /**
             * Creates a new compound listener.
             *
             * @param listeners The listeners to apply in their application order.
             */
            public Compound(List<? extends Listener> listeners) {
                this.listeners = listeners;
            }

            @Override
            public void onTransformation(TypeDescription typeDescription, DynamicType dynamicType) {
                for (Listener listener : listeners) {
                    listener.onTransformation(typeDescription, dynamicType);
                }
            }

            @Override
            public void onIgnored(TypeDescription typeDescription) {
                for (Listener listener : listeners) {
                    listener.onIgnored(typeDescription);
                }
            }

            @Override
            public void onError(String typeName, Throwable throwable) {
                for (Listener listener : listeners) {
                    listener.onError(typeName, throwable);
                }
            }

            @Override
            public void onComplete(String typeName) {
                for (Listener listener : listeners) {
                    listener.onComplete(typeName);
                }
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && listeners.equals(((Compound) other).listeners);
            }

            @Override
            public int hashCode() {
                return listeners.hashCode();
            }

            @Override
            public String toString() {
                return "AgentBuilder.Listener.Compound{" +
                        "listeners=" + listeners +
                        '}';
            }
        }
    }

    /**
     * An initialization strategy which determines the handling of {@link net.bytebuddy.implementation.LoadedTypeInitializer}s
     * and the loading of auxiliary types.
     */
    interface InitializationStrategy {

        /**
         * Creates a new dispatcher for injecting this initialization strategy during a transformation process.
         *
         * @return The dispatcher to be used.
         */
        Dispatcher dispatcher();

        /**
         * A dispatcher for changing a class file to adapt a self-initialization strategy.
         */
        interface Dispatcher {

            /**
             * Transforms the instrumented type to implement an appropriate initialization strategy.
             *
             * @param builder The builder which should implement the initialization strategy.
             * @return The given {@code builder} with the initialization strategy applied.
             */
            DynamicType.Builder<?> apply(DynamicType.Builder<?> builder);

            /**
             * Registers a dynamic type for initialization and/or begins the initialization process.
             *
             * @param dynamicType     The dynamic type that is created.
             * @param classLoader     The class loader of the dynamic type.
             * @param injectorFactory The injector factory
             */
            void register(DynamicType dynamicType, ClassLoader classLoader, InjectorFactory injectorFactory);

            /**
             * A factory for creating a {@link ClassInjector} only if it is required.
             */
            interface InjectorFactory {

                /**
                 * Resolves the class injector for this factory.
                 *
                 * @return The class injector for this factory.
                 */
                ClassInjector resolve();
            }
        }

        /**
         * A non-initializing initialization strategy.
         */
        enum NoOp implements InitializationStrategy, Dispatcher {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public Dispatcher dispatcher() {
                return this;
            }

            @Override
            public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
                return builder;
            }

            @Override
            public void register(DynamicType dynamicType, ClassLoader classLoader, InjectorFactory injectorFactory) {
                /* do nothing */
            }

            @Override
            public String toString() {
                return "AgentBuilder.InitializationStrategy.NoOp." + name();
            }
        }

        /**
         * An initialization strategy that adds a code block to an instrumented type's type initializer which
         * then calls a specific class that is responsible for the explicit initialization.
         */
        enum SelfInjection implements InitializationStrategy {

            /**
             * A form of self-injection where auxiliary types that are annotated by
             * {@link net.bytebuddy.implementation.auxiliary.AuxiliaryType.SignatureRelevant} of the instrumented type are loaded lazily and
             * any other auxiliary type is loaded eagerly.
             */
            SPLIT {
                @Override
                public InitializationStrategy.Dispatcher dispatcher() {
                    return new SelfInjection.Dispatcher.Split(random.nextInt());
                }
            },

            /**
             * A form of self-injection where any auxiliary type is loaded lazily.
             */
            LAZY {
                @Override
                public InitializationStrategy.Dispatcher dispatcher() {
                    return new SelfInjection.Dispatcher.Lazy(random.nextInt());
                }
            },

            /**
             * A form of self-injection where any auxiliary type is loaded eagerly.
             */
            EAGER {
                @Override
                public InitializationStrategy.Dispatcher dispatcher() {
                    return new SelfInjection.Dispatcher.Eager(random.nextInt());
                }
            };

            /**
             * A generator for random identification values.
             */
            protected final Random random;

            /**
             * Creates the self-injection strategy.
             */
            SelfInjection() {
                random = new Random();
            }

            @Override
            public String toString() {
                return "AgentBuilder.InitializationStrategy.SelfInjection." + name();
            }

            /**
             * A dispatcher for a self-initialization strategy.
             */
            protected abstract static class Dispatcher implements InitializationStrategy.Dispatcher {

                /**
                 * A random identification for the applied self-initialization.
                 */
                protected final int identification;

                /**
                 * Creates a new dispatcher.
                 *
                 * @param identification A random identification for the applied self-initialization.
                 */
                protected Dispatcher(int identification) {
                    this.identification = identification;
                }

                @Override
                public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
                    return builder.initializer(NexusAccessor.INSTANCE.identifiedBy(identification));
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && identification == ((Dispatcher) other).identification;
                }

                @Override
                public int hashCode() {
                    return identification;
                }

                /**
                 * A dispatcher for the {@link net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy.SelfInjection#SPLIT} strategy.
                 */
                protected static class Split extends Dispatcher {

                    /**
                     * Creates a new split dispatcher.
                     *
                     * @param identification A random identification for the applied self-initialization.
                     */
                    protected Split(int identification) {
                        super(identification);
                    }

                    @Override
                    public void register(DynamicType dynamicType, ClassLoader classLoader, InjectorFactory injectorFactory) {
                        Map<TypeDescription, byte[]> auxiliaryTypes = dynamicType.getAuxiliaryTypes();
                        LoadedTypeInitializer loadedTypeInitializer;
                        if (!auxiliaryTypes.isEmpty()) {
                            TypeDescription instrumentedType = dynamicType.getTypeDescription();
                            ClassInjector classInjector = injectorFactory.resolve();
                            Map<TypeDescription, byte[]> independentTypes = new LinkedHashMap<TypeDescription, byte[]>(auxiliaryTypes);
                            Map<TypeDescription, byte[]> dependentTypes = new LinkedHashMap<TypeDescription, byte[]>(auxiliaryTypes);
                            for (TypeDescription auxiliaryType : auxiliaryTypes.keySet()) {
                                (auxiliaryType.getDeclaredAnnotations().isAnnotationPresent(AuxiliaryType.SignatureRelevant.class)
                                        ? dependentTypes
                                        : independentTypes).remove(auxiliaryType);
                            }
                            Map<TypeDescription, LoadedTypeInitializer> loadedTypeInitializers = dynamicType.getLoadedTypeInitializers();
                            if (!independentTypes.isEmpty()) {
                                for (Map.Entry<TypeDescription, Class<?>> entry : classInjector.inject(independentTypes).entrySet()) {
                                    loadedTypeInitializers.get(entry.getKey()).onLoad(entry.getValue());
                                }
                            }
                            Map<TypeDescription, LoadedTypeInitializer> lazyInitializers = new HashMap<TypeDescription, LoadedTypeInitializer>(loadedTypeInitializers);
                            loadedTypeInitializers.keySet().removeAll(independentTypes.keySet());
                            loadedTypeInitializer = lazyInitializers.size() > 1 // there exist auxiliary types that need lazy loading
                                    ? new InjectingInitializer(instrumentedType, dependentTypes, lazyInitializers, classInjector)
                                    : lazyInitializers.get(instrumentedType);
                        } else {
                            loadedTypeInitializer = dynamicType.getLoadedTypeInitializers().get(dynamicType.getTypeDescription());
                        }
                        if (loadedTypeInitializer.isAlive()) {
                            NexusAccessor.INSTANCE.register(dynamicType.getTypeDescription().getName(), classLoader, identification, loadedTypeInitializer);
                        }
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.InitializationStrategy.SelfInjection.Dispatcher.Split{identification=" + identification + "}";
                    }
                }

                /**
                 * A dispatcher for the {@link net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy.SelfInjection#LAZY} strategy.
                 */
                protected static class Lazy extends Dispatcher {

                    /**
                     * Creates a new lazy dispatcher.
                     *
                     * @param identification A random identification for the applied self-initialization.
                     */
                    protected Lazy(int identification) {
                        super(identification);
                    }

                    @Override
                    public void register(DynamicType dynamicType, ClassLoader classLoader, InjectorFactory injectorFactory) {
                        Map<TypeDescription, byte[]> auxiliaryTypes = dynamicType.getAuxiliaryTypes();
                        LoadedTypeInitializer loadedTypeInitializer = auxiliaryTypes.isEmpty()
                                ? dynamicType.getLoadedTypeInitializers().get(dynamicType.getTypeDescription())
                                : new InjectingInitializer(dynamicType.getTypeDescription(), auxiliaryTypes, dynamicType.getLoadedTypeInitializers(), injectorFactory.resolve());
                        if (loadedTypeInitializer.isAlive()) {
                            NexusAccessor.INSTANCE.register(dynamicType.getTypeDescription().getName(), classLoader, identification, loadedTypeInitializer);
                        }
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.InitializationStrategy.SelfInjection.Dispatcher.Lazy{identification=" + identification + "}";
                    }
                }

                /**
                 * A dispatcher for the {@link net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy.SelfInjection#EAGER} strategy.
                 */
                protected static class Eager extends Dispatcher {

                    /**
                     * Creates a new eager dispatcher.
                     *
                     * @param identification A random identification for the applied self-initialization.
                     */
                    protected Eager(int identification) {
                        super(identification);
                    }

                    @Override
                    public void register(DynamicType dynamicType, ClassLoader classLoader, InjectorFactory injectorFactory) {
                        Map<TypeDescription, byte[]> auxiliaryTypes = dynamicType.getAuxiliaryTypes();
                        Map<TypeDescription, LoadedTypeInitializer> loadedTypeInitializers = dynamicType.getLoadedTypeInitializers();
                        if (!auxiliaryTypes.isEmpty()) {
                            for (Map.Entry<TypeDescription, Class<?>> entry : injectorFactory.resolve().inject(auxiliaryTypes).entrySet()) {
                                loadedTypeInitializers.get(entry.getKey()).onLoad(entry.getValue());
                            }
                        }
                        LoadedTypeInitializer loadedTypeInitializer = loadedTypeInitializers.get(dynamicType.getTypeDescription());
                        if (loadedTypeInitializer.isAlive()) {
                            NexusAccessor.INSTANCE.register(dynamicType.getTypeDescription().getName(), classLoader, identification, loadedTypeInitializer);
                        }
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.InitializationStrategy.SelfInjection.Dispatcher.Eager{identification=" + identification + "}";
                    }
                }

                /**
                 * A type initializer that injects all auxiliary types of the instrumented type.
                 */
                protected static class InjectingInitializer implements LoadedTypeInitializer {

                    /**
                     * The instrumented type.
                     */
                    private final TypeDescription instrumentedType;

                    /**
                     * The auxiliary types mapped to their class file representation.
                     */
                    private final Map<TypeDescription, byte[]> rawAuxiliaryTypes;

                    /**
                     * The instrumented types and auxiliary types mapped to their loaded type initializers.
                     * The instrumented types and auxiliary types mapped to their loaded type initializers.
                     */
                    private final Map<TypeDescription, LoadedTypeInitializer> loadedTypeInitializers;

                    /**
                     * The class injector to use.
                     */
                    private final ClassInjector classInjector;

                    /**
                     * Creates a new injection initializer.
                     *
                     * @param instrumentedType       The instrumented type.
                     * @param rawAuxiliaryTypes      The auxiliary types mapped to their class file representation.
                     * @param loadedTypeInitializers The instrumented types and auxiliary types mapped to their loaded type initializers.
                     * @param classInjector          The class injector to use.
                     */
                    protected InjectingInitializer(TypeDescription instrumentedType,
                                                   Map<TypeDescription, byte[]> rawAuxiliaryTypes,
                                                   Map<TypeDescription, LoadedTypeInitializer> loadedTypeInitializers,
                                                   ClassInjector classInjector) {
                        this.instrumentedType = instrumentedType;
                        this.rawAuxiliaryTypes = rawAuxiliaryTypes;
                        this.loadedTypeInitializers = loadedTypeInitializers;
                        this.classInjector = classInjector;
                    }

                    @Override
                    public void onLoad(Class<?> type) {
                        for (Map.Entry<TypeDescription, Class<?>> auxiliary : classInjector.inject(rawAuxiliaryTypes).entrySet()) {
                            loadedTypeInitializers.get(auxiliary.getKey()).onLoad(auxiliary.getValue());
                        }
                        loadedTypeInitializers.get(instrumentedType).onLoad(type);
                    }

                    @Override
                    public boolean isAlive() {
                        return true;
                    }

                    @Override
                    public boolean equals(Object o) {
                        if (this == o) return true;
                        if (o == null || getClass() != o.getClass()) return false;
                        InjectingInitializer that = (InjectingInitializer) o;
                        return classInjector.equals(that.classInjector)
                                && instrumentedType.equals(that.instrumentedType)
                                && rawAuxiliaryTypes.equals(that.rawAuxiliaryTypes)
                                && loadedTypeInitializers.equals(that.loadedTypeInitializers);
                    }

                    @Override
                    public int hashCode() {
                        int result = instrumentedType.hashCode();
                        result = 31 * result + rawAuxiliaryTypes.hashCode();
                        result = 31 * result + loadedTypeInitializers.hashCode();
                        result = 31 * result + classInjector.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.InitializationStrategy.SelfInjection.Dispatcher.InjectingInitializer{" +
                                "instrumentedType=" + instrumentedType +
                                ", rawAuxiliaryTypes=" + rawAuxiliaryTypes +
                                ", loadedTypeInitializers=" + loadedTypeInitializers +
                                ", classInjector=" + classInjector +
                                '}';
                    }
                }
            }

            /**
             * An accessor for making sure that the accessed {@link net.bytebuddy.agent.builder.Nexus} is the class that is loaded by the system class loader.
             */
            protected enum NexusAccessor {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                /**
                 * The dispatcher for registering type initializers in the {@link Nexus}.
                 */
                private final Dispatcher dispatcher;

                /**
                 * The {@link ClassLoader#getSystemClassLoader()} method.
                 */
                private final MethodDescription.InDefinedShape getSystemClassLoader;

                /**
                 * The {@link java.lang.ClassLoader#loadClass(String)} method.
                 */
                private final MethodDescription.InDefinedShape loadClass;

                /**
                 * The {@link Integer#valueOf(int)} method.
                 */
                private final MethodDescription.InDefinedShape valueOf;

                /**
                 * The {@link java.lang.Class#getDeclaredMethod(String, Class[])} method.
                 */
                private final MethodDescription getDeclaredMethod;

                /**
                 * The {@link java.lang.reflect.Method#invoke(Object, Object...)} method.
                 */
                private final MethodDescription invokeMethod;

                /**
                 * Creates the singleton accessor.
                 */
                @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "Explicit delegation of the exception")
                NexusAccessor() {
                    Dispatcher dispatcher;
                    try {
                        TypeDescription nexusType = new TypeDescription.ForLoadedType(Nexus.class);
                        dispatcher = new Dispatcher.Available(ClassInjector.UsingReflection.ofSystemClassLoader()
                                .inject(Collections.singletonMap(nexusType, ClassFileLocator.ForClassLoader.read(Nexus.class).resolve()))
                                .get(nexusType)
                                .getDeclaredMethod("register", String.class, ClassLoader.class, int.class, Object.class));
                    } catch (Exception exception) {
                        try {
                            dispatcher = new Dispatcher.Available(ClassLoader.getSystemClassLoader()
                                    .loadClass(Nexus.class.getName())
                                    .getDeclaredMethod("register", String.class, ClassLoader.class, int.class, Object.class));
                        } catch (Exception ignored) {
                            dispatcher = new Dispatcher.Unavailable(exception);
                        }
                    }
                    this.dispatcher = dispatcher;
                    getSystemClassLoader = new TypeDescription.ForLoadedType(ClassLoader.class).getDeclaredMethods()
                            .filter(named("getSystemClassLoader").and(takesArguments(0))).getOnly();
                    loadClass = new TypeDescription.ForLoadedType(ClassLoader.class).getDeclaredMethods()
                            .filter(named("loadClass").and(takesArguments(String.class))).getOnly();
                    getDeclaredMethod = new TypeDescription.ForLoadedType(Class.class).getDeclaredMethods()
                            .filter(named("getDeclaredMethod").and(takesArguments(String.class, Class[].class))).getOnly();
                    invokeMethod = new TypeDescription.ForLoadedType(Method.class).getDeclaredMethods()
                            .filter(named("invoke").and(takesArguments(Object.class, Object[].class))).getOnly();
                    valueOf = new TypeDescription.ForLoadedType(Integer.class).getDeclaredMethods()
                            .filter(named("valueOf").and(takesArguments(int.class))).getOnly();
                }

                /**
                 * Registers a type initializer with the class loader's nexus.
                 *
                 * @param name            The name of a type for which a loaded type initializer is registered.
                 * @param classLoader     The class loader for which a loaded type initializer is registered.
                 * @param identification  An identification for the initializer to run.
                 * @param typeInitializer The loaded type initializer to be registered.
                 */
                public void register(String name, ClassLoader classLoader, int identification, LoadedTypeInitializer typeInitializer) {
                    dispatcher.register(name, classLoader, identification, typeInitializer);
                }

                /**
                 * Creates a byte code appender for injecting a self-initializing type initializer block into the generated class.
                 *
                 * @param identification The identification of the initialization.
                 * @return An appropriate byte code appender.
                 */
                public ByteCodeAppender identifiedBy(int identification) {
                    return new InitializationAppender(identification);
                }

                @Override
                public String toString() {
                    return "AgentBuilder.InitializationStrategy.SelfInjection.NexusAccessor." + name();
                }

                /**
                 * A dispatcher for registering type initializers in the {@link Nexus}.
                 */
                protected interface Dispatcher {

                    /**
                     * Registers a type initializer with the class loader's nexus.
                     *
                     * @param name            The name of a type for which a loaded type initializer is registered.
                     * @param classLoader     The class loader for which a loaded type initializer is registered.
                     * @param identification  An identification for the initializer to run.
                     * @param typeInitializer The loaded type initializer to be registered.
                     */
                    void register(String name, ClassLoader classLoader, int identification, LoadedTypeInitializer typeInitializer);

                    /**
                     * An enabled dispatcher for registering a type initializer in a {@link Nexus}.
                     */
                    class Available implements Dispatcher {

                        /**
                         * Indicates that a static method is invoked by reflection.
                         */
                        private static final Object STATIC_METHOD = null;

                        /**
                         * The method for registering a type initializer in the system class loader's {@link Nexus}.
                         */
                        private final Method registration;

                        /**
                         * Creates a new dispatcher.
                         *
                         * @param registration The method for registering a type initializer in the system class loader's {@link Nexus}.
                         */
                        protected Available(Method registration) {
                            this.registration = registration;
                        }

                        @Override
                        public void register(String name, ClassLoader classLoader, int identification, LoadedTypeInitializer typeInitializer) {
                            try {
                                registration.invoke(STATIC_METHOD, name, classLoader, identification, typeInitializer);
                            } catch (IllegalAccessException exception) {
                                throw new IllegalStateException("Cannot register type initializer for " + name, exception);
                            } catch (InvocationTargetException exception) {
                                throw new IllegalStateException("Cannot register type initializer for " + name, exception.getCause());
                            }
                        }

                        @Override
                        public boolean equals(Object other) {
                            return this == other || !(other == null || getClass() != other.getClass())
                                    && registration.equals(((Available) other).registration);
                        }

                        @Override
                        public int hashCode() {
                            return registration.hashCode();
                        }

                        @Override
                        public String toString() {
                            return "AgentBuilder.InitializationStrategy.SelfInjection.NexusAccessor.Dispatcher.Available{" +
                                    "registration=" + registration +
                                    '}';
                        }
                    }

                    /**
                     * A disabled dispatcher where a {@link Nexus} is not available.
                     */
                    class Unavailable implements Dispatcher {

                        /**
                         * The exception that was raised during the dispatcher initialization.
                         */
                        private final Exception exception;

                        /**
                         * Creates a new disabled dispatcher.
                         *
                         * @param exception The exception that was raised during the dispatcher initialization.
                         */
                        protected Unavailable(Exception exception) {
                            this.exception = exception;
                        }

                        @Override
                        public void register(String name, ClassLoader classLoader, int identification, LoadedTypeInitializer typeInitializer) {
                            throw new IllegalStateException("Could not locate registration method", exception);
                        }

                        @Override
                        public boolean equals(Object other) {
                            return this == other || !(other == null || getClass() != other.getClass())
                                    && exception.equals(((Unavailable) other).exception);
                        }

                        @Override
                        public int hashCode() {
                            return exception.hashCode();
                        }

                        @Override
                        public String toString() {
                            return "AgentBuilder.InitializationStrategy.SelfInjection.NexusAccessor.Dispatcher.Unavailable{" +
                                    "exception=" + exception +
                                    '}';
                        }
                    }
                }

                /**
                 * A byte code appender for invoking a Nexus for initializing the instrumented type.
                 */
                protected static class InitializationAppender implements ByteCodeAppender {

                    /**
                     * The identification for the self-initialization to execute.
                     */
                    private final int identification;

                    /**
                     * Creates a new initialization appender.
                     *
                     * @param identification The identification for the self-initialization to execute.
                     */
                    protected InitializationAppender(int identification) {
                        this.identification = identification;
                    }

                    @Override
                    public Size apply(MethodVisitor methodVisitor, Implementation.Context implementationContext, MethodDescription instrumentedMethod) {
                        return new ByteCodeAppender.Simple(new StackManipulation.Compound(
                                MethodInvocation.invoke(NexusAccessor.INSTANCE.getSystemClassLoader),
                                new TextConstant(Nexus.class.getName()),
                                MethodInvocation.invoke(NexusAccessor.INSTANCE.loadClass),
                                new TextConstant("initialize"),
                                ArrayFactory.forType(new TypeDescription.Generic.OfNonGenericType.ForLoadedType(Class.class))
                                        .withValues(Arrays.asList(
                                                ClassConstant.of(TypeDescription.CLASS),
                                                ClassConstant.of(new TypeDescription.ForLoadedType(int.class)))),
                                MethodInvocation.invoke(NexusAccessor.INSTANCE.getDeclaredMethod),
                                NullConstant.INSTANCE,
                                ArrayFactory.forType(TypeDescription.Generic.OBJECT)
                                        .withValues(Arrays.asList(
                                                ClassConstant.of(instrumentedMethod.getDeclaringType().asErasure()),
                                                new StackManipulation.Compound(
                                                        IntegerConstant.forValue(identification),
                                                        MethodInvocation.invoke(INSTANCE.valueOf)))),
                                MethodInvocation.invoke(NexusAccessor.INSTANCE.invokeMethod),
                                Removal.SINGLE
                        )).apply(methodVisitor, implementationContext, instrumentedMethod);
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        InitializationAppender that = (InitializationAppender) other;
                        return identification == that.identification;
                    }

                    @Override
                    public int hashCode() {
                        return identification;
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.InitializationStrategy.SelfInjection.NexusAccessor.InitializationAppender{" +
                                "identification=" + identification +
                                '}';
                    }
                }
            }
        }

        /**
         * An initialization strategy that loads auxiliary types before loading the instrumented type. This strategy skips all types
         * that are a subtype of the instrumented type which would cause a premature loading of the instrumented type and abort
         * the instrumentation process.
         */
        enum Minimal implements InitializationStrategy, Dispatcher {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public Dispatcher dispatcher() {
                return this;
            }

            @Override
            public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
                return builder;
            }

            @Override
            public void register(DynamicType dynamicType, ClassLoader classLoader, InjectorFactory injectorFactory) {
                Map<TypeDescription, byte[]> auxiliaryTypes = dynamicType.getAuxiliaryTypes();
                Map<TypeDescription, byte[]> independentTypes = new LinkedHashMap<TypeDescription, byte[]>(auxiliaryTypes);
                for (TypeDescription auxiliaryType : auxiliaryTypes.keySet()) {
                    if (!auxiliaryType.getDeclaredAnnotations().isAnnotationPresent(AuxiliaryType.SignatureRelevant.class)) {
                        independentTypes.remove(auxiliaryType);
                    }
                }
                if (!independentTypes.isEmpty()) {
                    ClassInjector classInjector = injectorFactory.resolve();
                    Map<TypeDescription, LoadedTypeInitializer> loadedTypeInitializers = dynamicType.getLoadedTypeInitializers();
                    for (Map.Entry<TypeDescription, Class<?>> entry : classInjector.inject(independentTypes).entrySet()) {
                        loadedTypeInitializers.get(entry.getKey()).onLoad(entry.getValue());
                    }
                }
            }

            @Override
            public String toString() {
                return "AgentBuilder.InitializationStrategy.Minimal." + name();
            }
        }
    }

    /**
     * A redefinition strategy regulates how already loaded classes are modified by a built agent.
     */
    enum RedefinitionStrategy {

        /**
         * Disables redefinition such that already loaded classes are not affected by the agent.
         */
        DISABLED {
            @Override
            protected boolean isRetransforming(Instrumentation instrumentation) {
                return false;
            }

            @Override
            protected Collector makeCollector(Default.Transformation transformation) {
                throw new IllegalStateException("A disabled redefinition strategy cannot create a collector");
            }
        },

        /**
         * <p>
         * Applies a <b>redefinition</b> to all classes that are already loaded and that would have been transformed if
         * the built agent was registered before they were loaded.
         * </p>
         * <p>
         * <b>Important</b>: If a redefined class was previously instrumented, this instrumentation information is lost
         * during the instrumentation. The redefinition is applied upon the original byte code that is provided by a class
         * loader and not upon the code in its currently transformed format. Use
         * {@link net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy#RETRANSFORMATION} if this is a factual or
         * potential limitation.
         * </p>
         */
        REDEFINITION {
            @Override
            protected boolean isRetransforming(Instrumentation instrumentation) {
                if (!instrumentation.isRedefineClassesSupported()) {
                    throw new IllegalArgumentException("Cannot redefine classes: " + instrumentation);
                }
                return false;
            }

            @Override
            protected Collector makeCollector(Default.Transformation transformation) {
                return new Collector.ForRedefinition(transformation);
            }
        },

        /**
         * Applies a <b>retransformation</b> to all classes that are already loaded and that would have been transformed if
         * the built agent was registered before they were loaded.
         */
        RETRANSFORMATION {
            @Override
            protected boolean isRetransforming(Instrumentation instrumentation) {
                if (!instrumentation.isRetransformClassesSupported()) {
                    throw new IllegalArgumentException("Cannot retransform classes: " + instrumentation);
                }
                return true;
            }

            @Override
            protected Collector makeCollector(Default.Transformation transformation) {
                return new Collector.ForRetransformation(transformation);
            }
        };

        /**
         * Indicates if this strategy requires a class file transformer to be registered with a hint to apply the
         * transformer for retransformation.
         *
         * @param instrumentation The instrumentation instance used.
         * @return {@code true} if a class file transformer must be registered with a hint for retransformation.
         */
        protected abstract boolean isRetransforming(Instrumentation instrumentation);

        /**
         * Indicates that this redefinition strategy applies a modification of already loaded classes.
         *
         * @return {@code true} if this redefinition strategy applies a modification of already loaded classes.
         */
        protected boolean isEnabled() {
            return this != DISABLED;
        }

        /**
         * Creates a collector instance that is responsible for collecting loaded classes for potential retransformation.
         *
         * @param transformation The transformation that is registered for the agent.
         * @return A new collector for collecting already loaded classes for transformation.
         */
        protected abstract Collector makeCollector(Default.Transformation transformation);

        @Override
        public String toString() {
            return "AgentBuilder.RedefinitionStrategy." + name();
        }

        /**
         * A collector is responsible for collecting classes that are to be considered for modification.
         */
        protected interface Collector {

            /**
             * Considers a loaded class for modification.
             *
             * @param type         The type that is to be considered.
             * @param ignoredTypes A matcher that indicates what types are explicitly ignored.
             * @return {@code true} if the class is considered to be redefined.
             */
            boolean consider(Class<?> type, ElementMatcher<? super TypeDescription> ignoredTypes);

            /**
             * Applies the represented type modification on all collected types.
             *
             * @param instrumentation            The instrumentation to use.
             * @param byteBuddy                  The Byte Buddy configuration to use.
             * @param binaryLocator              The binary locator to use.
             * @param typeStrategy               The type strategy to use.
             * @param listener                   The listener to report to.
             * @param nativeMethodStrategy       The native method strategy to apply.
             * @param accessControlContext       The access control context to use.
             * @param initializationStrategy     The initialization strategy to use.
             * @param bootstrapInjectionStrategy The bootstrap injection strategy to use.
             * @throws UnmodifiableClassException If an unmodifiable class is attempted to be modified.
             * @throws ClassNotFoundException     If a class cannot be found while redefining another class.
             */
            void apply(Instrumentation instrumentation,
                       ByteBuddy byteBuddy,
                       BinaryLocator binaryLocator,
                       TypeStrategy typeStrategy,
                       Listener listener,
                       Default.NativeMethodStrategy nativeMethodStrategy,
                       AccessControlContext accessControlContext,
                       InitializationStrategy initializationStrategy,
                       Default.BootstrapInjectionStrategy bootstrapInjectionStrategy) throws UnmodifiableClassException, ClassNotFoundException;

            /**
             * A collector that applies a <b>redefinition</b> of already loaded classes.
             */
            class ForRedefinition implements Collector {

                /**
                 * The transformation of the built agent.
                 */
                private final Default.Transformation transformation;

                /**
                 * A list of already collected redefinitions.
                 */
                private final List<Entry> entries;

                /**
                 * Creates a new collector for a redefinition.
                 *
                 * @param transformation The transformation of the built agent.
                 */
                protected ForRedefinition(Default.Transformation transformation) {
                    this.transformation = transformation;
                    entries = new ArrayList<Entry>();
                }

                @Override
                public boolean consider(Class<?> type, ElementMatcher<? super TypeDescription> ignoredTypes) {
                    Default.Transformation.Resolution resolution = transformation.resolve(new TypeDescription.ForLoadedType(type),
                            type.getClassLoader(),
                            type,
                            type.getProtectionDomain(),
                            ignoredTypes);
                    return resolution.isResolved() && entries.add(new Entry(type, resolution));
                }

                @Override
                public void apply(Instrumentation instrumentation,
                                  ByteBuddy byteBuddy,
                                  BinaryLocator binaryLocator,
                                  TypeStrategy typeStrategy,
                                  Listener listener,
                                  Default.NativeMethodStrategy nativeMethodStrategy,
                                  AccessControlContext accessControlContext,
                                  InitializationStrategy initializationStrategy,
                                  Default.BootstrapInjectionStrategy bootstrapInjectionStrategy) throws UnmodifiableClassException, ClassNotFoundException {
                    List<ClassDefinition> classDefinitions = new ArrayList<ClassDefinition>(entries.size());
                    for (Entry entry : entries) {
                        TypeDescription typeDescription = new TypeDescription.ForLoadedType(entry.getType());
                        try {
                            classDefinitions.add(entry.resolve(initializationStrategy,
                                    binaryLocator.classFileLocator(entry.getType().getClassLoader()),
                                    typeStrategy,
                                    byteBuddy,
                                    nativeMethodStrategy,
                                    bootstrapInjectionStrategy,
                                    accessControlContext,
                                    listener));
                        } catch (Throwable throwable) {
                            listener.onError(typeDescription.getName(), throwable);
                        } finally {
                            listener.onComplete(typeDescription.getName());
                        }
                    }
                    if (!classDefinitions.isEmpty()) {
                        instrumentation.redefineClasses(classDefinitions.toArray(new ClassDefinition[classDefinitions.size()]));
                    }
                }

                @Override
                public String toString() {
                    return "AgentBuilder.RedefinitionStrategy.Collector.ForRedefinition{" +
                            "transformation=" + transformation +
                            ", entries=" + entries +
                            '}';
                }

                /**
                 * An entry describing a type redefinition.
                 */
                protected static class Entry {

                    /**
                     * The type to be redefined.
                     */
                    private final Class<?> type;

                    /**
                     * The resolved transformation for this type.
                     */
                    private final Default.Transformation.Resolution resolution;

                    /**
                     * @param type       The type to be redefined.
                     * @param resolution The resolved transformation for this type.
                     */
                    protected Entry(Class<?> type, Default.Transformation.Resolution resolution) {
                        this.type = type;
                        this.resolution = resolution;
                    }

                    /**
                     * Returns the type that is being redefined.
                     *
                     * @return The type that is being redefined.
                     */
                    public Class<?> getType() {
                        return type;
                    }

                    /**
                     * Resolves this entry into a fully defined class redefinition.
                     *
                     * @param initializationStrategy     The initialization strategy to use.
                     * @param classFileLocator           The class file locator to use.
                     * @param typeStrategy               The type strategy to use.
                     * @param byteBuddy                  The Byte Buddy configuration to use.
                     * @param nativeMethodStrategy       The native method strategy to use.
                     * @param bootstrapInjectionStrategy The bootstrap injection strategy to use.
                     * @param accessControlContext       The access control context to use.
                     * @param listener                   The listener to report to.
                     * @return An appropriate class definition.
                     */
                    protected ClassDefinition resolve(InitializationStrategy initializationStrategy,
                                                      ClassFileLocator classFileLocator,
                                                      TypeStrategy typeStrategy,
                                                      ByteBuddy byteBuddy,
                                                      Default.NativeMethodStrategy nativeMethodStrategy,
                                                      Default.BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                                      AccessControlContext accessControlContext,
                                                      Listener listener) {
                        return new ClassDefinition(type, resolution.apply(initializationStrategy,
                                classFileLocator,
                                typeStrategy,
                                byteBuddy,
                                nativeMethodStrategy,
                                bootstrapInjectionStrategy,
                                accessControlContext,
                                listener));
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        Entry entry = (Entry) other;
                        return type.equals(entry.type) && resolution.equals(entry.resolution);
                    }

                    @Override
                    public int hashCode() {
                        int result = type.hashCode();
                        result = 31 * result + resolution.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.RedefinitionStrategy.Collector.ForRedefinition.Entry{" +
                                "type=" + type +
                                ", resolution=" + resolution +
                                '}';
                    }
                }
            }

            /**
             * A collector that applies a <b>retransformation</b> of already loaded classes.
             */
            class ForRetransformation implements Collector {

                /**
                 * The transformation defined by the built agent.
                 */
                private final Default.Transformation transformation;

                /**
                 * The types that were collected for retransformation.
                 */
                private final List<Class<?>> types;

                /**
                 * Creates a new collector for a retransformation.
                 *
                 * @param transformation The transformation defined by the built agent.
                 */
                protected ForRetransformation(Default.Transformation transformation) {
                    this.transformation = transformation;
                    types = new ArrayList<Class<?>>();
                }

                @Override
                public boolean consider(Class<?> type, ElementMatcher<? super TypeDescription> ignoredTypes) {
                    return transformation.resolve(new TypeDescription.ForLoadedType(type),
                            type.getClassLoader(), type, type.getProtectionDomain(), ignoredTypes).isResolved() && types.add(type);
                }

                @Override
                public void apply(Instrumentation instrumentation,
                                  ByteBuddy byteBuddy,
                                  BinaryLocator binaryLocator,
                                  TypeStrategy typeStrategy,
                                  Listener listener,
                                  Default.NativeMethodStrategy nativeMethodStrategy,
                                  AccessControlContext accessControlContext,
                                  InitializationStrategy initializationStrategy,
                                  Default.BootstrapInjectionStrategy bootstrapInjectionStrategy) throws UnmodifiableClassException {
                    if (!types.isEmpty()) {
                        instrumentation.retransformClasses(types.toArray(new Class<?>[types.size()]));
                    }
                }

                @Override
                public String toString() {
                    return "AgentBuilder.RedefinitionStrategy.Collector.ForRetransformation{" +
                            "transformation=" + transformation +
                            ", types=" + types +
                            '}';
                }
            }
        }
    }

    /**
     * Implements the instrumentation of the {@code LambdaMetafactory} if this feature is enabled.
     */
    enum LambdaInstrumentationStrategy implements Callable<Class<?>> {

        /**
         * A strategy that enables instrumentation of the {@code LambdaMetafactory} if such a factory exists on the current VM.
         * Classes representing lambda expressions that are created by Byte Buddy are fully compatible to those created by
         * the JVM and can be serialized or deserialized to one another. The classes do however show a few differences:
         * <ul>
         * <li>Byte Buddy's classes are public with a public constructor. Doing so, it is not necessary to instantiate a
         * non-capturing lambda expression by reflection. This is done because Byte Buddy is not necessarily capable
         * of using reflection due to an active security manager.</li>
         * <li>Byte Buddy's classes are not marked as synthetic as an agent builder does not instrument synthetic classes
         * by default.</li>
         * </ul>
         */
        ENABLED {
            @Override
            protected void apply(ByteBuddy byteBuddy,
                                 Default.BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                 Instrumentation instrumentation,
                                 ClassFileTransformer classFileTransformer) {
                if (LambdaFactory.register(classFileTransformer, new LambdaInstanceFactory(byteBuddy, bootstrapInjectionStrategy), this)) {
                    Class<?> lambdaMetaFactory;
                    try {
                        lambdaMetaFactory = Class.forName("java.lang.invoke.LambdaMetafactory");
                    } catch (ClassNotFoundException ignored) {
                        return;
                    }
                    byteBuddy.with(Implementation.Context.Disabled.Factory.INSTANCE)
                            .redefine(lambdaMetaFactory)
                            .visit(new AsmVisitorWrapper.ForDeclaredMethods()
                                    .method(named("metafactory"), MetaFactoryRedirection.INSTANCE)
                                    .method(named("altMetafactory"), AlternativeMetaFactoryRedirection.INSTANCE))
                            .make()
                            .load(lambdaMetaFactory.getClassLoader(), ClassReloadingStrategy.of(instrumentation));
                }
            }

            @Override
            public Class<?> call() throws Exception {
                TypeDescription lambdaFactory = new TypeDescription.ForLoadedType(LambdaFactory.class);
                return ClassInjector.UsingReflection.ofSystemClassLoader()
                        .inject(Collections.singletonMap(lambdaFactory, ClassFileLocator.ForClassLoader.read(LambdaFactory.class).resolve()))
                        .get(lambdaFactory);
            }
        },

        /**
         * A strategy that does not instrument the {@code LambdaMetafactory}.
         */
        DISABLED {
            @Override
            protected void apply(ByteBuddy byteBuddy,
                                 Default.BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                 Instrumentation instrumentation,
                                 ClassFileTransformer classFileTransformer) {
                    /* do nothing */
            }

            @Override
            public Class<?> call() throws Exception {
                throw new IllegalStateException("Cannot inject LambdaFactory from disabled instrumentation strategy");
            }
        };

        /**
         * Indicates that an original implementation can be ignored when redefining a method.
         */
        protected static final MethodVisitor IGNORE_ORIGINAL = null;

        /**
         * Releases the supplied class file transformer when it was built with {@link AgentBuilder#with(LambdaInstrumentationStrategy)} enabled.
         * Subsequently, the class file transformer is no longer applied when a class that represents a lambda expression is created.
         *
         * @param classFileTransformer The class file transformer to release.
         * @param instrumentation      The instrumentation instance that is used to potentially rollback the instrumentation of the {@code LambdaMetafactory}.
         */
        public static void release(ClassFileTransformer classFileTransformer, Instrumentation instrumentation) {
            if (LambdaFactory.release(classFileTransformer)) {
                try {
                    ClassReloadingStrategy.of(instrumentation).reset(Class.forName("java.lang.invoke.LambdaMetafactory"));
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not release lambda transformer", exception);
                }
            }
        }

        /**
         * Returns an enabled lambda instrumentation strategy for {@code true}.
         *
         * @param enabled If lambda instrumentation should be enabled.
         * @return {@code true} if the returned strategy should be enabled.
         */
        public static LambdaInstrumentationStrategy of(boolean enabled) {
            return enabled
                    ? ENABLED
                    : DISABLED;
        }

        /**
         * Applies a transformation to lambda instances if applicable.
         *
         * @param byteBuddy                  The Byte Buddy instance to use.
         * @param bootstrapInjectionStrategy
         * @param instrumentation            The instrumentation instance for applying a redefinition.
         * @param classFileTransformer       The class file transformer to apply.
         */
        protected abstract void apply(ByteBuddy byteBuddy,
                                      Default.BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                      Instrumentation instrumentation,
                                      ClassFileTransformer classFileTransformer);

        /**
         * Indicates if this strategy enables instrumentation of the {@code LambdaMetafactory}.
         *
         * @return {@code true} if this strategy is enabled.
         */
        public boolean isEnabled() {
            return this == ENABLED;
        }

        @Override
        public String toString() {
            return "AgentBuilder.LambdaInstrumentationStrategy." + name();
        }

        /**
         * A factory that creates instances that represent lambda expressions.
         */
        protected static class LambdaInstanceFactory {

            /**
             * The name of a factory for a lambda expression.
             */
            private static final String LAMBDA_FACTORY = "get$Lambda";

            /**
             * A prefix for a field that represents a property of a lambda expression.
             */
            private static final String FIELD_PREFIX = "arg$";

            /**
             * The infix to use for naming classes that represent lambda expression. The additional prefix
             * is necessary because the subsequent counter is not sufficient to keep names unique compared
             * to the original factory.
             */
            private static final String LAMBDA_TYPE_INFIX = "$$Lambda$ByteBuddy$";

            /**
             * A type-safe constant to express that a class is not already loaded when applying a class file transformer.
             */
            private static final Class<?> NOT_PREVIOUSLY_DEFINED = null;

            /**
             * A counter for naming lambda expressions randomly.
             */
            private static final AtomicInteger LAMBDA_NAME_COUNTER = new AtomicInteger();

            /**
             * The Byte Buddy instance to use for creating lambda objects.
             */
            private final ByteBuddy byteBuddy;

            private final Default.BootstrapInjectionStrategy bootstrapInjectionStrategy;

            /**
             * Creates a new lambda instance factory.
             *
             * @param byteBuddy                  The Byte Buddy instance to use for creating lambda objects.
             * @param bootstrapInjectionStrategy
             */
            protected LambdaInstanceFactory(ByteBuddy byteBuddy, Default.BootstrapInjectionStrategy bootstrapInjectionStrategy) {
                this.byteBuddy = byteBuddy;
                this.bootstrapInjectionStrategy = bootstrapInjectionStrategy;
            }

            /**
             * Applies this lambda meta factory.
             *
             * @param targetTypeLookup            A lookup context representing the creating class of this lambda expression.
             * @param lambdaMethodName            The name of the lambda expression's represented method.
             * @param factoryMethodType           The type of the lambda expression's represented method.
             * @param lambdaMethodType            The type of the lambda expression's factory method.
             * @param targetMethodHandle          A handle representing the target of the lambda expression's method.
             * @param specializedLambdaMethodType A specialization of the type of the lambda expression's represented method.
             * @param serializable                {@code true} if the lambda expression should be serializable.
             * @param markerInterfaces            A list of interfaces for the lambda expression to represent.
             * @param additionalBridges           A list of additional bridge methods to be implemented by the lambda expression.
             * @param classFileTransformers       A collection of class file transformers to apply when creating the class.
             * @return A binary representation of the transformed class file.
             */
            public Class<?> make(Object targetTypeLookup,
                                 String lambdaMethodName,
                                 Object factoryMethodType,
                                 Object lambdaMethodType,
                                 Object targetMethodHandle,
                                 Object specializedLambdaMethodType,
                                 boolean serializable,
                                 List<Class<?>> markerInterfaces,
                                 List<?> additionalBridges,
                                 Collection<? extends ClassFileTransformer> classFileTransformers) {
                JavaInstance.MethodType factoryMethod = JavaInstance.MethodType.ofLoaded(factoryMethodType);
                JavaInstance.MethodType lambdaMethod = JavaInstance.MethodType.ofLoaded(lambdaMethodType);
                JavaInstance.MethodHandle targetMethod = JavaInstance.MethodHandle.ofLoaded(targetMethodHandle, targetTypeLookup);
                JavaInstance.MethodType specializedLambdaMethod = JavaInstance.MethodType.ofLoaded(specializedLambdaMethodType);
                Class<?> targetType = JavaInstance.MethodHandle.lookupType(targetTypeLookup);
                String lambdaClassName = targetType.getName() + LAMBDA_TYPE_INFIX + LAMBDA_NAME_COUNTER.incrementAndGet();
                DynamicType.Builder<?> builder = byteBuddy
                        .subclass(factoryMethod.getReturnType(), ConstructorStrategy.Default.NO_CONSTRUCTORS)
                        .modifiers(TypeManifestation.FINAL, Visibility.PUBLIC)
                        .implement(markerInterfaces)
                        .name(lambdaClassName)
                        .defineConstructor(Visibility.PUBLIC)
                        .withParameters(factoryMethod.getParameterTypes())
                        .intercept(ConstructorImplementation.INSTANCE)
                        .method(named(lambdaMethodName)
                                .and(takesArguments(lambdaMethod.getParameterTypes()))
                                .and(returns(lambdaMethod.getReturnType())))
                        .intercept(new LambdaMethodImplementation(targetMethod, specializedLambdaMethod))
                        .defineField("lambda$methodHandle", JavaType.METHOD_HANDLE.getTypeStub(), Visibility.PUBLIC, Ownership.STATIC);
                int index = 0;
                for (TypeDescription capturedType : factoryMethod.getParameterTypes()) {
                    builder = builder.defineField(FIELD_PREFIX + ++index, capturedType, Visibility.PRIVATE, FieldManifestation.FINAL);
                }
                if (!factoryMethod.getParameterTypes().isEmpty()) {
                    builder = builder.defineMethod(LAMBDA_FACTORY, factoryMethod.getReturnType(), Visibility.PRIVATE, Ownership.STATIC)
                            .withParameters(factoryMethod.getParameterTypes())
                            .intercept(FactoryImplementation.INSTANCE);
                }
                if (serializable) {
                    if (!markerInterfaces.contains(Serializable.class)) {
                        builder = builder.implement(Serializable.class);
                    }
                    builder = builder.defineMethod("writeReplace", Object.class, Visibility.PRIVATE)
                            .intercept(new SerializationImplementation(new TypeDescription.ForLoadedType(targetType),
                                    factoryMethod.getReturnType(),
                                    lambdaMethodName,
                                    lambdaMethod,
                                    targetMethod,
                                    JavaInstance.MethodType.ofLoaded(specializedLambdaMethodType)));
                } else if (factoryMethod.getReturnType().isAssignableTo(Serializable.class)) {
                    builder = builder.defineMethod("readObject", void.class, Visibility.PRIVATE)
                            .withParameters(ObjectInputStream.class)
                            .throwing(NotSerializableException.class)
                            .intercept(ExceptionMethod.throwing(NotSerializableException.class, "Non-serializable lambda"))
                            .defineMethod("writeObject", void.class, Visibility.PRIVATE)
                            .withParameters(ObjectOutputStream.class)
                            .throwing(NotSerializableException.class)
                            .intercept(ExceptionMethod.throwing(NotSerializableException.class, "Non-serializable lambda"));
                }
                for (Object additionalBridgeType : additionalBridges) {
                    JavaInstance.MethodType additionalBridge = JavaInstance.MethodType.ofLoaded(additionalBridgeType);
                    builder = builder.defineMethod(lambdaMethodName, additionalBridge.getReturnType(), MethodManifestation.BRIDGE, Visibility.PUBLIC)
                            .withParameters(additionalBridge.getParameterTypes())
                            .intercept(new BridgeMethodImplementation(lambdaMethodName, lambdaMethod));
                }
                DynamicType.Unloaded<?> lambdaImplementation = builder.make();
                byte[] classFile = lambdaImplementation.getBytes();
                for (ClassFileTransformer classFileTransformer : classFileTransformers) {
                    try {
                        byte[] transformedClassFile = classFileTransformer.transform(targetType.getClassLoader(),
                                lambdaClassName.replace('.', '/'),
                                NOT_PREVIOUSLY_DEFINED,
                                targetType.getProtectionDomain(),
                                classFile);
                        classFile = transformedClassFile == null
                                ? classFile
                                : transformedClassFile;
                    } catch (Throwable ignored) {
                            /* do nothing */
                    }
                }
                Class<?> lambdaType = (targetType.getClassLoader() == null
                        ? bootstrapInjectionStrategy.make(targetType.getProtectionDomain())
                        : new ClassInjector.UsingReflection(targetType.getClassLoader()))
                        .inject(Collections.singletonMap(lambdaImplementation.getTypeDescription(), classFile))
                        .get(lambdaImplementation.getTypeDescription());
                try {
                    lambdaType.getDeclaredField("lambda$methodHandle").set(null, targetMethodHandle);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
                return lambdaType;
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && byteBuddy.equals(((LambdaInstanceFactory) other).byteBuddy);
            }

            @Override
            public int hashCode() {
                return byteBuddy.hashCode();
            }

            @Override
            public String toString() {
                return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory{" +
                        "byteBuddy=" + byteBuddy +
                        '}';
            }

            /**
             * Implements a lambda class's constructor.
             */
            protected enum ConstructorImplementation implements Implementation {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                /**
                 * A reference to the {@link Object} class's default constructor.
                 */
                private final MethodDescription.InDefinedShape objectConstructor;

                /**
                 * Creates a new constructor implementation.
                 */
                ConstructorImplementation() {
                    objectConstructor = TypeDescription.OBJECT.getDeclaredMethods().filter(isConstructor()).getOnly();
                }

                @Override
                public ByteCodeAppender appender(Target implementationTarget) {
                    return new Appender(implementationTarget.getInstrumentedType().getDeclaredFields().filter(not(isStatic())));
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.ConstructorImplementation." + name();
                }

                /**
                 * An appender to implement the constructor.
                 */
                protected static class Appender implements ByteCodeAppender {

                    /**
                     * The fields that are declared by the instrumented type.
                     */
                    private final List<FieldDescription.InDefinedShape> lambdaArguments;

                    /**
                     * Creates a new appender.
                     *
                     * @param lambdaArguments The fields that are declared by the instrumented type.
                     */
                    protected Appender(List<FieldDescription.InDefinedShape> lambdaArguments) {
                        this.lambdaArguments = lambdaArguments;
                    }

                    @Override
                    public Size apply(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                        List<StackManipulation> fieldAssignments = new ArrayList<StackManipulation>(lambdaArguments.size() * 3);
                        for (ParameterDescription parameterDescription : instrumentedMethod.getParameters()) {
                            fieldAssignments.add(MethodVariableAccess.REFERENCE.loadOffset(0));
                            fieldAssignments.add(MethodVariableAccess.of(parameterDescription.getType()).loadOffset(parameterDescription.getOffset()));
                            fieldAssignments.add(FieldAccess.forField(lambdaArguments.get(parameterDescription.getIndex())).putter());
                        }
                        return new Size(new StackManipulation.Compound(
                                MethodVariableAccess.REFERENCE.loadOffset(0),
                                MethodInvocation.invoke(INSTANCE.objectConstructor),
                                new StackManipulation.Compound(fieldAssignments),
                                MethodReturn.VOID
                        ).apply(methodVisitor, implementationContext).getMaximalSize(), instrumentedMethod.getStackSize());
                    }

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && lambdaArguments.equals(((Appender) other).lambdaArguments);
                    }

                    @Override
                    public int hashCode() {
                        return lambdaArguments.hashCode();
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.ConstructorImplementation.Appender{" +
                                "lambdaArguments=" + lambdaArguments +
                                '}';
                    }
                }
            }

            /**
             * An implementation of a instance factory for a lambda expression's class.
             */
            protected enum FactoryImplementation implements Implementation {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public ByteCodeAppender appender(Target implementationTarget) {
                    return new Appender(implementationTarget.getInstrumentedType());
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.FactoryImplementation." + name();
                }

                /**
                 * An appender for a lambda expression factory.
                 */
                protected static class Appender implements ByteCodeAppender {

                    /**
                     * The instrumented type.
                     */
                    private final TypeDescription instrumentedType;

                    /**
                     * Creates a new appender.
                     *
                     * @param instrumentedType The instrumented type.
                     */
                    protected Appender(TypeDescription instrumentedType) {
                        this.instrumentedType = instrumentedType;
                    }

                    @Override
                    public Size apply(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                        return new Size(new StackManipulation.Compound(
                                TypeCreation.of(instrumentedType),
                                Duplication.SINGLE,
                                MethodVariableAccess.allArgumentsOf(instrumentedMethod),
                                MethodInvocation.invoke(instrumentedType.getDeclaredMethods().filter(isConstructor()).getOnly()),
                                MethodReturn.REFERENCE
                        ).apply(methodVisitor, implementationContext).getMaximalSize(), instrumentedMethod.getStackSize());
                    }

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && instrumentedType.equals(((Appender) other).instrumentedType);
                    }

                    @Override
                    public int hashCode() {
                        return instrumentedType.hashCode();
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.FactoryImplementation.Appender{" +
                                "instrumentedType=" + instrumentedType +
                                '}';
                    }
                }
            }

            /**
             * Implements a lambda expression's functional method.
             */
            protected static class LambdaMethodImplementation implements Implementation {

                /**
                 * The handle of the target method of the lambda expression.
                 */
                private final JavaInstance.MethodHandle targetMethod;

                /**
                 * The specialized type of the lambda method.
                 */
                private final JavaInstance.MethodType specializedLambdaMethod;

                /**
                 * Creates a implementation of a lambda expression's functional method.
                 *
                 * @param targetMethod            The target method of the lambda expression.
                 * @param specializedLambdaMethod The specialized type of the lambda method.
                 */
                protected LambdaMethodImplementation(JavaInstance.MethodHandle targetMethod, JavaInstance.MethodType specializedLambdaMethod) {
                    this.targetMethod = targetMethod;
                    this.specializedLambdaMethod = specializedLambdaMethod;
                }

                @Override
                public ByteCodeAppender appender(Target implementationTarget) {
                    return new Appender(targetMethod.getOwnerType()
                            .getDeclaredMethods()
                            .filter(named(targetMethod.getName())
                                    .and(returns(targetMethod.getReturnType()))
                                    .and(takesArguments(targetMethod.getParameterTypes())))
                            .getOnly(),
                            specializedLambdaMethod,
                            implementationTarget.getInstrumentedType().getDeclaredFields());
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    LambdaMethodImplementation that = (LambdaMethodImplementation) other;
                    return targetMethod.equals(that.targetMethod)
                            && specializedLambdaMethod.equals(that.specializedLambdaMethod);
                }

                @Override
                public int hashCode() {
                    int result = targetMethod.hashCode();
                    result = 31 * result + specializedLambdaMethod.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.LambdaMethodImplementation{" +
                            "targetMethod=" + targetMethod +
                            ", specializedLambdaMethod=" + specializedLambdaMethod +
                            '}';
                }

                /**
                 * An appender for a lambda expression's functional method.
                 */
                protected static class Appender implements ByteCodeAppender {

                    /**
                     * The target method of the lambda expression.
                     */
                    private final MethodDescription targetMethod;

                    /**
                     * The specialized type of the lambda method.
                     */
                    private final JavaInstance.MethodType specializedLambdaMethod;

                    /**
                     * The instrumented type's declared fields.
                     */
                    private final List<FieldDescription.InDefinedShape> lambdaArguments;

                    /**
                     * Creates an appender of a lambda expression's functional method.
                     *
                     * @param targetMethod            The target method of the lambda expression.
                     * @param specializedLambdaMethod The specialized type of the lambda method.
                     * @param lambdaArguments         The instrumented type's declared fields.
                     */
                    protected Appender(MethodDescription targetMethod,
                                       JavaInstance.MethodType specializedLambdaMethod,
                                       List<FieldDescription.InDefinedShape> lambdaArguments) {
                        this.targetMethod = targetMethod;
                        this.specializedLambdaMethod = specializedLambdaMethod;
                        this.lambdaArguments = lambdaArguments;
                    }

                    @Override
                    public Size apply(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                        List<StackManipulation> fieldAccess = new ArrayList<StackManipulation>(lambdaArguments.size() * 2);
                        fieldAccess.add(FieldAccess.forField(lambdaArguments.get(0)).getter());
                        for (FieldDescription.InDefinedShape fieldDescription : lambdaArguments.subList(1, lambdaArguments.size())) {
                            fieldAccess.add(MethodVariableAccess.REFERENCE.loadOffset(0));
                            fieldAccess.add(FieldAccess.forField(fieldDescription).getter());
                        }
                        List<StackManipulation> parameterAccess = new ArrayList<StackManipulation>(instrumentedMethod.getParameters().size() * 2);
                        for (ParameterDescription parameterDescription : instrumentedMethod.getParameters()) {
                            parameterAccess.add(MethodVariableAccess.of(parameterDescription.getType()).loadOffset(parameterDescription.getOffset()));
                            parameterAccess.add(Assigner.DEFAULT.assign(parameterDescription.getType(),
                                    specializedLambdaMethod.getParameterTypes().get(parameterDescription.getIndex()).asGenericType(),
                                    Assigner.Typing.DYNAMIC));
                        }
                        return new Size(new StackManipulation.Compound(
                                new StackManipulation.Compound(fieldAccess),
                                new StackManipulation.Compound(parameterAccess),
                                new HandleInvocation(specializedLambdaMethod),
                                MethodReturn.returning(targetMethod.getReturnType().asErasure())
                        ).apply(methodVisitor, implementationContext).getMaximalSize(), instrumentedMethod.getStackSize());
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        Appender appender = (Appender) other;
                        return targetMethod.equals(appender.targetMethod)
                                && lambdaArguments.equals(appender.lambdaArguments)
                                && specializedLambdaMethod.equals(appender.specializedLambdaMethod);
                    }

                    @Override
                    public int hashCode() {
                        int result = targetMethod.hashCode();
                        result = 31 * result + lambdaArguments.hashCode();
                        result = 31 * result + specializedLambdaMethod.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.LambdaMethodImplementation.Appender{" +
                                "targetMethod=" + targetMethod +
                                ", specializedLambdaMethod=" + specializedLambdaMethod +
                                ", lambdaArguments=" + lambdaArguments +
                                '}';
                    }
                }
            }

            /**
             * Implements the {@code writeReplace} method for serializable lambda expressions.
             */
            protected static class SerializationImplementation implements Implementation {

                /**
                 * The lambda expression's declaring type.
                 */
                private final TypeDescription targetType;

                /**
                 * The lambda expression's functional type.
                 */
                private final TypeDescription lambdaType;

                /**
                 * The lambda expression's functional method name.
                 */
                private final String lambdaMethodName;

                /**
                 * The method type of the lambda expression's functional method.
                 */
                private final JavaInstance.MethodType lambdaMethod;

                /**
                 * A handle that references the lambda expressions invocation target.
                 */
                private final JavaInstance.MethodHandle targetMethod;

                /**
                 * The specialized method type of the lambda expression's functional method.
                 */
                private final JavaInstance.MethodType specializedMethod;

                /**
                 * Creates a new implementation for a serializable's lambda expression's {@code writeReplace} method.
                 *
                 * @param targetType        The lambda expression's declaring type.
                 * @param lambdaType        The lambda expression's functional type.
                 * @param lambdaMethodName  The lambda expression's functional method name.
                 * @param lambdaMethod      The method type of the lambda expression's functional method.
                 * @param targetMethod      A handle that references the lambda expressions invocation target.
                 * @param specializedMethod The specialized method type of the lambda expression's functional method.
                 */
                protected SerializationImplementation(TypeDescription targetType,
                                                      TypeDescription lambdaType,
                                                      String lambdaMethodName,
                                                      JavaInstance.MethodType lambdaMethod,
                                                      JavaInstance.MethodHandle targetMethod,
                                                      JavaInstance.MethodType specializedMethod) {
                    this.targetType = targetType;
                    this.lambdaType = lambdaType;
                    this.lambdaMethodName = lambdaMethodName;
                    this.lambdaMethod = lambdaMethod;
                    this.targetMethod = targetMethod;
                    this.specializedMethod = specializedMethod;
                }

                @Override
                public ByteCodeAppender appender(Target implementationTarget) {
                    TypeDescription serializedLambda;
                    try {
                        serializedLambda = new TypeDescription.ForLoadedType(Class.forName("java.lang.invoke.SerializedLambda"));
                    } catch (ClassNotFoundException exception) {
                        throw new IllegalStateException("Cannot find class for lambda serialization", exception);
                    }
                    List<FieldDescription.InDefinedShape> lambdaArguments = implementationTarget.getInstrumentedType().getDeclaredFields().filter(not(isStatic()));
                    List<StackManipulation> lambdaArgumentsLoads = new ArrayList<StackManipulation>(lambdaArguments.size());
                    for (FieldDescription.InDefinedShape lambdaArgument : lambdaArguments) {
                        lambdaArgumentsLoads.add(new StackManipulation.Compound(MethodVariableAccess.REFERENCE.loadOffset(0),
                                FieldAccess.forField(lambdaArgument).getter(),
                                Assigner.DEFAULT.assign(lambdaArgument.getType(), TypeDescription.Generic.OBJECT, Assigner.Typing.STATIC)));
                    }
                    return new ByteCodeAppender.Simple(new StackManipulation.Compound(
                            TypeCreation.of(serializedLambda),
                            Duplication.SINGLE,
                            ClassConstant.of(targetType),
                            new TextConstant(lambdaType.getInternalName()),
                            new TextConstant(lambdaMethodName),
                            new TextConstant(lambdaMethod.getDescriptor()),
                            IntegerConstant.forValue(targetMethod.getHandleType().getIdentifier()),
                            new TextConstant(targetMethod.getOwnerType().getInternalName()),
                            new TextConstant(targetMethod.getName()),
                            new TextConstant(targetMethod.getDescriptor()),
                            new TextConstant(specializedMethod.getDescriptor()),
                            ArrayFactory.forType(TypeDescription.Generic.OBJECT).withValues(lambdaArgumentsLoads),
                            MethodInvocation.invoke(serializedLambda.getDeclaredMethods().filter(isConstructor()).getOnly()),
                            MethodReturn.REFERENCE
                    ));
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    SerializationImplementation that = (SerializationImplementation) other;
                    return targetType.equals(that.targetType)
                            && lambdaType.equals(that.lambdaType)
                            && lambdaMethodName.equals(that.lambdaMethodName)
                            && lambdaMethod.equals(that.lambdaMethod)
                            && targetMethod.equals(that.targetMethod)
                            && specializedMethod.equals(that.specializedMethod);
                }

                @Override
                public int hashCode() {
                    int result = targetType.hashCode();
                    result = 31 * result + lambdaType.hashCode();
                    result = 31 * result + lambdaMethodName.hashCode();
                    result = 31 * result + lambdaMethod.hashCode();
                    result = 31 * result + targetMethod.hashCode();
                    result = 31 * result + specializedMethod.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.SerializationImplementation{" +
                            "targetType=" + targetType +
                            ", lambdaType=" + lambdaType +
                            ", lambdaMethodName='" + lambdaMethodName + '\'' +
                            ", lambdaMethod=" + lambdaMethod +
                            ", targetMethod=" + targetMethod +
                            ", specializedMethod=" + specializedMethod +
                            '}';
                }
            }

            /**
             * Implements an explicit bridge method for a lambda expression.
             */
            protected static class BridgeMethodImplementation implements Implementation {

                /**
                 * The name of the lambda expression's functional method.
                 */
                private final String lambdaMethodName;

                /**
                 * The actual type of the lambda expression's functional method.
                 */
                private final JavaInstance.MethodType lambdaMethod;

                /**
                 * Creates a new bridge method implementation for a lambda expression.
                 *
                 * @param lambdaMethodName The name of the lambda expression's functional method.
                 * @param lambdaMethod     The actual type of the lambda expression's functional method.
                 */
                protected BridgeMethodImplementation(String lambdaMethodName, JavaInstance.MethodType lambdaMethod) {
                    this.lambdaMethodName = lambdaMethodName;
                    this.lambdaMethod = lambdaMethod;
                }

                @Override
                public ByteCodeAppender appender(Target implementationTarget) {
                    return new Appender(implementationTarget.invokeSuper(new MethodDescription.SignatureToken(lambdaMethodName,
                            lambdaMethod.getReturnType(),
                            lambdaMethod.getParameterTypes())));
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    BridgeMethodImplementation that = (BridgeMethodImplementation) other;
                    return lambdaMethodName.equals(that.lambdaMethodName) && lambdaMethod.equals(that.lambdaMethod);
                }

                @Override
                public int hashCode() {
                    int result = lambdaMethodName.hashCode();
                    result = 31 * result + lambdaMethod.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.BridgeMethodImplementation{" +
                            "lambdaMethodName='" + lambdaMethodName + '\'' +
                            ", lambdaMethod=" + lambdaMethod +
                            '}';
                }

                /**
                 * An appender for implementing a bridge method for a lambda expression.
                 */
                protected static class Appender implements ByteCodeAppender {

                    /**
                     * The invocation of the bridge's target method.
                     */
                    private final SpecialMethodInvocation bridgeTargetInvocation;

                    /**
                     * Creates a new appender for invoking a lambda expression's bridge method target.
                     *
                     * @param bridgeTargetInvocation The invocation of the bridge's target method.
                     */
                    protected Appender(SpecialMethodInvocation bridgeTargetInvocation) {
                        this.bridgeTargetInvocation = bridgeTargetInvocation;
                    }

                    @Override
                    public Size apply(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
                        return new Compound(new Simple(
                                MethodVariableAccess.allArgumentsOf(instrumentedMethod)
                                        .asBridgeOf(bridgeTargetInvocation.getMethodDescription())
                                        .prependThisReference(),
                                bridgeTargetInvocation,
                                bridgeTargetInvocation.getMethodDescription().getReturnType().asErasure().isAssignableTo(instrumentedMethod.getReturnType().asErasure())
                                        ? StackManipulation.Trivial.INSTANCE
                                        : TypeCasting.to(instrumentedMethod.getReceiverType().asErasure()),
                                MethodReturn.returning(instrumentedMethod.getReturnType().asErasure())

                        )).apply(methodVisitor, implementationContext, instrumentedMethod);
                    }

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && bridgeTargetInvocation.equals(((Appender) other).bridgeTargetInvocation);
                    }

                    @Override
                    public int hashCode() {
                        return bridgeTargetInvocation.hashCode();
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.LambdaInstrumentationStrategy.LambdaInstanceFactory.BridgeMethodImplementation.Appender{" +
                                "bridgeTargetInvocation=" + bridgeTargetInvocation +
                                '}';
                    }
                }
            }
        }

        /**
         * Implements the regular lambda meta factory. The implementation represents the following code:
         * <blockquote><pre>
         * public static CallSite metafactory(MethodHandles.Lookup caller,
         *     String invokedName,
         *     MethodType invokedType,
         *     MethodType samMethodType,
         *     MethodHandle implMethod,
         *     MethodType instantiatedMethodType) throws Exception {
         *   Unsafe unsafe = Unsafe.getUnsafe();
         *   {@code Class<?>} lambdaClass = unsafe.defineAnonymousClass(caller.lookupClass(),
         *       (byte[]) ClassLoader.getSystemClassLoader().loadClass("net.bytebuddy.agent.builder.LambdaFactory").getDeclaredMethod("make",
         *           Object.class,
         *           String.class,
         *           Object.class,
         *           Object.class,
         *           Object.class,
         *           Object.class,
         *           boolean.class,
         *           List.class,
         *           List.class).invoke(null,
         *               caller,
         *               invokedName,
         *               invokedType,
         *               samMethodType,
         *               implMethod,
         *               instantiatedMethodType,
         *               false,
         *               Collections.emptyList(),
         *               Collections.emptyList()),
         *       null);
         *   unsafe.ensureClassInitialized(lambdaClass);
         *   return invokedType.parameterCount() == 0
         *     ? new ConstantCallSite(MethodHandles.constant(invokedType.returnType(), lambdaClass.getDeclaredConstructors()[0].newInstance()))
         *     : new ConstantCallSite(MethodHandles.Lookup.IMPL_LOOKUP.findStatic(lambdaClass, "get$Lambda", invokedType));
         * </pre></blockquote>
         */
        protected enum MetaFactoryRedirection implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public MethodVisitor wrap(TypeDescription instrumentedType, MethodDescription.InDefinedShape methodDescription, MethodVisitor methodVisitor) {
                methodVisitor.visitCode();
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "sun/misc/Unsafe", "getUnsafe", "()Lsun/misc/Unsafe;", false);
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 6);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 6);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
                methodVisitor.visitLdcInsn("net.bytebuddy.agent.builder.LambdaFactory");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
                methodVisitor.visitLdcInsn("make");
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 9);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/String;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_2);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_3);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_4);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_5);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 6);
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 7);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/util/List;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 8);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/util/List;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 9);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_2);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_3);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_4);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 4);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_5);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 5);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 6);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 7);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 8);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "Ljava/lang/Class;");
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 7);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodType", "parameterCount", "()I", false);
                Label conditionalDefault = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IFNE, conditionalDefault);
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodType", "returnType", "()Ljava/lang/Class;", false);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 7);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;", false);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "constant", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
                Label conditionalAlternative = new Label();
                methodVisitor.visitJumpInsn(Opcodes.GOTO, conditionalAlternative);
                methodVisitor.visitLabel(conditionalDefault);
                methodVisitor.visitFrame(Opcodes.F_APPEND, 2, new Object[]{"sun/misc/Unsafe", "java/lang/Class"}, 0, null);
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/invoke/MethodHandles$Lookup", "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;");
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 7);
                methodVisitor.visitLdcInsn("get$Lambda");
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
                methodVisitor.visitLabel(conditionalAlternative);
                methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/invoke/CallSite"});
                methodVisitor.visitInsn(Opcodes.ARETURN);
                methodVisitor.visitMaxs(8, 8);
                methodVisitor.visitEnd();
                return IGNORE_ORIGINAL;
            }

            @Override
            public String toString() {
                return "AgentBuilder.LambdaInstrumentationStrategy.MetaFactoryRedirection." + name();
            }
        }

        /**
         * Implements the alternative lambda meta factory. The implementation represents the following code:
         * <blockquote><pre>
         * public static CallSite altMetafactory(MethodHandles.Lookup caller,
         *     String invokedName,
         *     MethodType invokedType,
         *     Object... args) throws Exception {
         *   int flags = (Integer) args[3];
         *   int argIndex = 4;
         *   {@code Class<?>[]} markerInterface;
         *   if ((flags {@code &} FLAG_MARKERS) != 0) {
         *     int markerCount = (Integer) args[argIndex++];
         *     markerInterface = new {@code Class<?>}[markerCount];
         *     System.arraycopy(args, argIndex, markerInterface, 0, markerCount);
         *     argIndex += markerCount;
         *   } else {
         *     markerInterface = new {@code Class<?>}[0];
         *   }
         *   MethodType[] additionalBridge;
         *   if ((flags {@code &} FLAG_BRIDGES) != 0) {
         *     int bridgeCount = (Integer) args[argIndex++];
         *     additionalBridge = new MethodType[bridgeCount];
         *     System.arraycopy(args, argIndex, additionalBridge, 0, bridgeCount);
         *     // argIndex += bridgeCount;
         *   } else {
         *     additionalBridge = new MethodType[0];
         *   }
         *   Unsafe unsafe = Unsafe.getUnsafe();
         *   {@code Class<?>} lambdaClass = unsafe.defineAnonymousClass(caller.lookupClass(),
         *       (byte[]) ClassLoader.getSystemClassLoader().loadClass("net.bytebuddy.agent.builder.LambdaFactory").getDeclaredMethod("make",
         *           Object.class,
         *           String.class,
         *           Object.class,
         *           Object.class,
         *           Object.class,
         *           Object.class,
         *           boolean.class,
         *           List.class,
         *           List.class).invoke(null,
         *               caller,
         *               invokedName,
         *               invokedType,
         *               args[0],
         *               args[1],
         *               args[2],
         *               (flags {@code &} FLAG_SERIALIZABLE) != 0,
         *               Arrays.asList(markerInterface),
         *               Arrays.asList(additionalBridge)),
         *       null);
         *   unsafe.ensureClassInitialized(lambdaClass);
         *   return invokedType.parameterCount() == 0
         *     ? new ConstantCallSite(MethodHandles.constant(invokedType.returnType(), lambdaClass.getDeclaredConstructors()[0].newInstance()))
         *     : new ConstantCallSite(MethodHandles.Lookup.IMPL_LOOKUP.findStatic(lambdaClass, "get$Lambda", invokedType));
         * }
         * </pre></blockquote>
         */
        protected enum AlternativeMetaFactoryRedirection implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public MethodVisitor wrap(TypeDescription instrumentedType, MethodDescription.InDefinedShape methodDescription, MethodVisitor methodVisitor) {
                methodVisitor.visitCode();
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitInsn(Opcodes.ICONST_3);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, 4);
                methodVisitor.visitInsn(Opcodes.ICONST_4);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, 5);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 4);
                methodVisitor.visitInsn(Opcodes.ICONST_2);
                methodVisitor.visitInsn(Opcodes.IAND);
                Label markerInterfaceLoop = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, markerInterfaceLoop);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 5);
                methodVisitor.visitIincInsn(5, 1);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, 7);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 7);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 6);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 5);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 6);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 7);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 5);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 7);
                methodVisitor.visitInsn(Opcodes.IADD);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, 5);
                Label markerInterfaceExit = new Label();
                methodVisitor.visitJumpInsn(Opcodes.GOTO, markerInterfaceExit);
                methodVisitor.visitLabel(markerInterfaceLoop);
                methodVisitor.visitFrame(Opcodes.F_APPEND, 2, new Object[]{Opcodes.INTEGER, Opcodes.INTEGER}, 0, null);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 6);
                methodVisitor.visitLabel(markerInterfaceExit);
                methodVisitor.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"[Ljava/lang/Class;"}, 0, null);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 4);
                methodVisitor.visitInsn(Opcodes.ICONST_4);
                methodVisitor.visitInsn(Opcodes.IAND);
                Label additionalBridgesLoop = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, additionalBridgesLoop);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 5);
                methodVisitor.visitIincInsn(5, 1);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, 8);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 8);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/invoke/MethodType");
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 7);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 5);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 7);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 8);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
                Label additionalBridgesExit = new Label();
                methodVisitor.visitJumpInsn(Opcodes.GOTO, additionalBridgesExit);
                methodVisitor.visitLabel(additionalBridgesLoop);
                methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/invoke/MethodType");
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 7);
                methodVisitor.visitLabel(additionalBridgesExit);
                methodVisitor.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"[Ljava/lang/invoke/MethodType;"}, 0, null);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "sun/misc/Unsafe", "getUnsafe", "()Lsun/misc/Unsafe;", false);
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 8);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 8);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
                methodVisitor.visitLdcInsn("net.bytebuddy.agent.builder.LambdaFactory");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
                methodVisitor.visitLdcInsn("make");
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 9);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/String;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_2);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_3);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_4);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_5);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 6);
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 7);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/util/List;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 8);
                methodVisitor.visitLdcInsn(Type.getType("Ljava/util/List;"));
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 9);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_2);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_3);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_4);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ICONST_5);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
                methodVisitor.visitInsn(Opcodes.ICONST_2);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 6);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, 4);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitInsn(Opcodes.IAND);
                Label callSiteConditional = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, callSiteConditional);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                Label callSiteAlternative = new Label();
                methodVisitor.visitJumpInsn(Opcodes.GOTO, callSiteAlternative);
                methodVisitor.visitLabel(callSiteConditional);
                methodVisitor.visitFrame(Opcodes.F_FULL, 9, new Object[]{"java/lang/invoke/MethodHandles$Lookup", "java/lang/String", "java/lang/invoke/MethodType", "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/lang/Class;", "[Ljava/lang/invoke/MethodType;", "sun/misc/Unsafe"}, 7, new Object[]{"sun/misc/Unsafe", "java/lang/Class", "java/lang/reflect/Method", Opcodes.NULL, "[Ljava/lang/Object;", "[Ljava/lang/Object;", Opcodes.INTEGER});
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitLabel(callSiteAlternative);
                methodVisitor.visitFrame(Opcodes.F_FULL, 9, new Object[]{"java/lang/invoke/MethodHandles$Lookup", "java/lang/String", "java/lang/invoke/MethodType", "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/lang/Class;", "[Ljava/lang/invoke/MethodType;", "sun/misc/Unsafe"}, 8, new Object[]{"sun/misc/Unsafe", "java/lang/Class", "java/lang/reflect/Method", Opcodes.NULL, "[Ljava/lang/Object;", "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER});
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 7);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 6);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitIntInsn(Opcodes.BIPUSH, 8);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 7);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
                methodVisitor.visitInsn(Opcodes.AASTORE);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[B");
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "defineAnonymousClass", "(Ljava/lang/Class;[B[Ljava/lang/Object;)Ljava/lang/Class;", false);
                methodVisitor.visitVarInsn(Opcodes.ASTORE, 9);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 8);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 9);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "ensureClassInitialized", "(Ljava/lang/Class;)V", false);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodType", "parameterCount", "()I", false);
                Label callSiteJump = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IFNE, callSiteJump);
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodType", "returnType", "()Ljava/lang/Class;", false);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 9);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;", false);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitInsn(Opcodes.AALOAD);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "constant", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
                Label callSiteExit = new Label();
                methodVisitor.visitJumpInsn(Opcodes.GOTO, callSiteExit);
                methodVisitor.visitLabel(callSiteJump);
                methodVisitor.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/Class"}, 0, null);
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/invoke/MethodHandles$Lookup", "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;");
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 9);
                methodVisitor.visitLdcInsn("get$Lambda");
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
                methodVisitor.visitLabel(callSiteExit);
                methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/invoke/CallSite"});
                methodVisitor.visitInsn(Opcodes.ARETURN);
                methodVisitor.visitMaxs(9, 10);
                methodVisitor.visitEnd();
                return IGNORE_ORIGINAL;
            }

            @Override
            public String toString() {
                return "AgentBuilder.LambdaInstrumentationStrategy.AlternativeMetaFactoryRedirection." + name();
            }
        }
    }

    /**
     * The default implementation of an {@link net.bytebuddy.agent.builder.AgentBuilder}.
     */
    class Default implements AgentBuilder {

        /**
         * The name of the Byte Buddy {@code net.bytebuddy.agent.Installer} class.
         */
        private static final String INSTALLER_TYPE = "net.bytebuddy.agent.Installer";

        /**
         * The name of the {@code net.bytebuddy.agent.Installer} field containing an installed {@link Instrumentation}.
         */
        private static final String INSTRUMENTATION_FIELD = "instrumentation";

        /**
         * Indicator for access to a static member via reflection to make the code more readable.
         */
        private static final Object STATIC_FIELD = null;

        /**
         * The value that is to be returned from a {@link java.lang.instrument.ClassFileTransformer} to indicate
         * that no class file transformation is to be applied.
         */
        private static final byte[] NO_TRANSFORMATION = null;

        /**
         * The {@link net.bytebuddy.ByteBuddy} instance to be used.
         */
        private final ByteBuddy byteBuddy;

        /**
         * The binary locator to use.
         */
        private final BinaryLocator binaryLocator;

        /**
         * The definition handler to use.
         */
        private final TypeStrategy typeStrategy;

        /**
         * The listener to notify on transformations.
         */
        private final Listener listener;

        /**
         * The native method strategy to use.
         */
        private final NativeMethodStrategy nativeMethodStrategy;

        /**
         * The access control context to use for loading classes.
         */
        private final AccessControlContext accessControlContext;

        /**
         * The initialization strategy to use for creating classes.
         */
        private final InitializationStrategy initializationStrategy;

        /**
         * The redefinition strategy to apply.
         */
        private final RedefinitionStrategy redefinitionStrategy;

        /**
         * The injection strategy for injecting classes into the bootstrap class loader.
         */
        private final BootstrapInjectionStrategy bootstrapInjectionStrategy;

        /**
         * A strategy to determine of the {@code LambdaMetfactory} should be instrumented to allow for the instrumentation
         * of classes that represent lambda expressions.
         */
        private final LambdaInstrumentationStrategy lambdaInstrumentationStrategy;

        /**
         * A matcher that indicates any type that should be excluded from instrumentation.
         */
        private final ElementMatcher<? super TypeDescription> ignoredTypes;

        /**
         * The transformation object for handling type transformations.
         */
        private final Transformation transformation;

        /**
         * Creates a new default agent builder that uses a default {@link net.bytebuddy.ByteBuddy} instance for
         * creating classes.
         */
        public Default() {
            this(new ByteBuddy());
        }

        /**
         * Creates a new agent builder with default settings.
         *
         * @param byteBuddy The Byte Buddy instance to be used.
         */
        public Default(ByteBuddy byteBuddy) {
            this(byteBuddy,
                    BinaryLocator.Default.FAST,
                    TypeStrategy.Default.REBASE,
                    Listener.NoOp.INSTANCE,
                    NativeMethodStrategy.Disabled.INSTANCE,
                    AccessController.getContext(),
                    InitializationStrategy.SelfInjection.SPLIT,
                    RedefinitionStrategy.DISABLED,
                    BootstrapInjectionStrategy.Disabled.INSTANCE,
                    LambdaInstrumentationStrategy.DISABLED,
                    isSynthetic(),
                    Transformation.Ignored.INSTANCE);
        }

        /**
         * Creates a new default agent builder.
         *
         * @param byteBuddy                     The Byte Buddy instance to be used.
         * @param binaryLocator                 The binary locator to use.
         * @param typeStrategy                  The definition handler to use.
         * @param listener                      The listener to notify on transformations.
         * @param nativeMethodStrategy          The native method strategy to apply.
         * @param accessControlContext          The access control context to use for loading classes.
         * @param initializationStrategy        The initialization strategy to use for transformed types.
         * @param redefinitionStrategy          The redefinition strategy to apply.
         * @param bootstrapInjectionStrategy    The injection strategy for injecting classes into the bootstrap class loader.
         * @param lambdaInstrumentationStrategy A strategy to determine of the {@code LambdaMetfactory} should be instrumented to allow for the
         *                                      instrumentation of classes that represent lambda expressions.
         * @param ignoredTypes                  A matcher that indicates any type that should be excluded from instrumentation.
         * @param transformation                The transformation object for handling type transformations.
         */
        protected Default(ByteBuddy byteBuddy,
                          BinaryLocator binaryLocator,
                          TypeStrategy typeStrategy,
                          Listener listener,
                          NativeMethodStrategy nativeMethodStrategy,
                          AccessControlContext accessControlContext,
                          InitializationStrategy initializationStrategy,
                          RedefinitionStrategy redefinitionStrategy,
                          BootstrapInjectionStrategy bootstrapInjectionStrategy,
                          LambdaInstrumentationStrategy lambdaInstrumentationStrategy,
                          ElementMatcher<? super TypeDescription> ignoredTypes,
                          Transformation transformation) {
            this.byteBuddy = byteBuddy;
            this.binaryLocator = binaryLocator;
            this.typeStrategy = typeStrategy;
            this.listener = listener;
            this.nativeMethodStrategy = nativeMethodStrategy;
            this.accessControlContext = accessControlContext;
            this.initializationStrategy = initializationStrategy;
            this.redefinitionStrategy = redefinitionStrategy;
            this.bootstrapInjectionStrategy = bootstrapInjectionStrategy;
            this.lambdaInstrumentationStrategy = lambdaInstrumentationStrategy;
            this.ignoredTypes = ignoredTypes;
            this.transformation = transformation;
        }

        @Override
        public Identified type(RawMatcher matcher) {
            return new Matched(matcher, Transformer.NoOp.INSTANCE);
        }

        @Override
        public Identified type(ElementMatcher<? super TypeDescription> typeMatcher) {
            return type(typeMatcher, any());
        }

        @Override
        public Identified type(ElementMatcher<? super TypeDescription> typeMatcher, ElementMatcher<? super ClassLoader> classLoaderMatcher) {
            return type(new RawMatcher.ForElementMatcherPair(typeMatcher, classLoaderMatcher));
        }

        @Override
        public AgentBuilder with(ByteBuddy byteBuddy) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(Listener listener) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    new Listener.Compound(this.listener, listener),
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(TypeStrategy typeStrategy) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(BinaryLocator binaryLocator) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder enableNativeMethodPrefix(String prefix) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    NativeMethodStrategy.ForPrefix.of(prefix),
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder disableNativeMethodPrefix() {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    NativeMethodStrategy.Disabled.INSTANCE,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(AccessControlContext accessControlContext) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(RedefinitionStrategy redefinitionStrategy) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(InitializationStrategy initializationStrategy) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder enableBootstrapInjection(File folder, Instrumentation instrumentation) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    new BootstrapInjectionStrategy.Enabled(folder, instrumentation),
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder disableBootstrapInjection() {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    BootstrapInjectionStrategy.Disabled.INSTANCE,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder ignore(ElementMatcher<? super TypeDescription> ignoredTypes) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public AgentBuilder with(LambdaInstrumentationStrategy lambdaInstrumentationStrategy) {
            return new Default(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    redefinitionStrategy,
                    bootstrapInjectionStrategy,
                    lambdaInstrumentationStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public ClassFileTransformer makeRaw() {
            return new ExecutingTransformer(byteBuddy,
                    binaryLocator,
                    typeStrategy,
                    listener,
                    nativeMethodStrategy,
                    accessControlContext,
                    initializationStrategy,
                    bootstrapInjectionStrategy,
                    ignoredTypes,
                    transformation);
        }

        @Override
        public ClassFileTransformer installOn(Instrumentation instrumentation) {
            ClassFileTransformer classFileTransformer = makeRaw();
            instrumentation.addTransformer(classFileTransformer, redefinitionStrategy.isRetransforming(instrumentation));
            if (nativeMethodStrategy.isEnabled(instrumentation)) {
                instrumentation.setNativeMethodPrefix(classFileTransformer, nativeMethodStrategy.getPrefix());
            }
            lambdaInstrumentationStrategy.apply(byteBuddy, bootstrapInjectionStrategy, instrumentation, classFileTransformer);
            if (redefinitionStrategy.isEnabled()) {
                RedefinitionStrategy.Collector collector = redefinitionStrategy.makeCollector(transformation);
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    TypeDescription typeDescription = new TypeDescription.ForLoadedType(type);
                    try {
                        if (!instrumentation.isModifiableClass(type) || !collector.consider(type, ignoredTypes)) {
                            try {
                                try {
                                    listener.onIgnored(typeDescription);
                                } finally {
                                    listener.onComplete(typeDescription.getName());
                                }
                            } catch (Throwable ignored) {
                                // Ignore exceptions that are thrown by listeners to mimic the behavior of a transformation.
                            }
                        }
                    } catch (Throwable throwable) {
                        try {
                            try {
                                listener.onError(typeDescription.getName(), throwable);
                            } finally {
                                listener.onComplete(typeDescription.getName());
                            }
                        } catch (Throwable ignored) {
                            // Ignore exceptions that are thrown by listeners to mimic the behavior of a transformation.
                        }
                    }
                }
                try {
                    collector.apply(instrumentation,
                            byteBuddy,
                            binaryLocator,
                            typeStrategy,
                            listener,
                            nativeMethodStrategy,
                            accessControlContext,
                            initializationStrategy,
                            bootstrapInjectionStrategy);
                } catch (UnmodifiableClassException exception) {
                    throw new IllegalStateException("Cannot modify at least one class: " + collector, exception);
                } catch (ClassNotFoundException exception) {
                    throw new IllegalStateException("Cannot find at least one class class: " + collector, exception);
                }
            }
            return classFileTransformer;
        }

        @Override
        public ClassFileTransformer installOnByteBuddyAgent() {
            try {
                Instrumentation instrumentation = (Instrumentation) ClassLoader.getSystemClassLoader()
                        .loadClass(INSTALLER_TYPE)
                        .getDeclaredField(INSTRUMENTATION_FIELD)
                        .get(STATIC_FIELD);
                if (instrumentation == null) {
                    throw new IllegalStateException("The Byte Buddy agent is not installed");
                }
                return installOn(instrumentation);
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("The Byte Buddy agent is not installed or not accessible", exception);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Default aDefault = (Default) other;
            return binaryLocator.equals(aDefault.binaryLocator)
                    && byteBuddy.equals(aDefault.byteBuddy)
                    && listener.equals(aDefault.listener)
                    && nativeMethodStrategy.equals(aDefault.nativeMethodStrategy)
                    && typeStrategy.equals(aDefault.typeStrategy)
                    && accessControlContext.equals(aDefault.accessControlContext)
                    && initializationStrategy == aDefault.initializationStrategy
                    && redefinitionStrategy == aDefault.redefinitionStrategy
                    && bootstrapInjectionStrategy.equals(aDefault.bootstrapInjectionStrategy)
                    && lambdaInstrumentationStrategy.equals(aDefault.lambdaInstrumentationStrategy)
                    && ignoredTypes.equals(aDefault.ignoredTypes)
                    && transformation.equals(aDefault.transformation);
        }

        @Override
        public int hashCode() {
            int result = byteBuddy.hashCode();
            result = 31 * result + binaryLocator.hashCode();
            result = 31 * result + listener.hashCode();
            result = 31 * result + typeStrategy.hashCode();
            result = 31 * result + nativeMethodStrategy.hashCode();
            result = 31 * result + accessControlContext.hashCode();
            result = 31 * result + initializationStrategy.hashCode();
            result = 31 * result + redefinitionStrategy.hashCode();
            result = 31 * result + bootstrapInjectionStrategy.hashCode();
            result = 31 * result + lambdaInstrumentationStrategy.hashCode();
            result = 31 * result + ignoredTypes.hashCode();
            result = 31 * result + transformation.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "AgentBuilder.Default{" +
                    "byteBuddy=" + byteBuddy +
                    ", binaryLocator=" + binaryLocator +
                    ", typeStrategy=" + typeStrategy +
                    ", listener=" + listener +
                    ", nativeMethodStrategy=" + nativeMethodStrategy +
                    ", accessControlContext=" + accessControlContext +
                    ", initializationStrategy=" + initializationStrategy +
                    ", redefinitionStrategy=" + redefinitionStrategy +
                    ", bootstrapInjectionStrategy=" + bootstrapInjectionStrategy +
                    ", lambdaInstrumentationStrategy=" + lambdaInstrumentationStrategy +
                    ", ignoredTypes=" + ignoredTypes +
                    ", transformation=" + transformation +
                    '}';
        }

        /**
         * An injection strategy for injecting classes into the bootstrap class loader.
         */
        protected interface BootstrapInjectionStrategy {

            /**
             * Creates an injector for the bootstrap class loader.
             *
             * @param protectionDomain The protection domain to be used.
             * @return A class injector for the bootstrap class loader.
             */
            ClassInjector make(ProtectionDomain protectionDomain);

            /**
             * A disabled bootstrap injection strategy.
             */
            enum Disabled implements BootstrapInjectionStrategy {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public ClassInjector make(ProtectionDomain protectionDomain) {
                    throw new IllegalStateException("Injecting classes into the bootstrap class loader was not enabled");
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.BootstrapInjectionStrategy.Disabled." + name();
                }
            }

            /**
             * An enabled bootstrap injection strategy.
             */
            class Enabled implements BootstrapInjectionStrategy {

                /**
                 * The folder in which jar files are to be saved.
                 */
                private final File folder;

                /**
                 * The instrumentation to use for appending jar files.
                 */
                private final Instrumentation instrumentation;

                /**
                 * Creates a new enabled bootstrap class loader injection strategy.
                 *
                 * @param folder          The folder in which jar files are to be saved.
                 * @param instrumentation The instrumentation to use for appending jar files.
                 */
                public Enabled(File folder, Instrumentation instrumentation) {
                    this.folder = folder;
                    this.instrumentation = instrumentation;
                }

                @Override
                public ClassInjector make(ProtectionDomain protectionDomain) {
                    return ClassInjector.UsingInstrumentation.of(folder, ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, instrumentation);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Enabled enabled = (Enabled) other;
                    return folder.equals(enabled.folder) && instrumentation.equals(enabled.instrumentation);
                }

                @Override
                public int hashCode() {
                    int result = folder.hashCode();
                    result = 31 * result + instrumentation.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.BootstrapInjectionStrategy.Enabled{" +
                            "folder=" + folder +
                            ", instrumentation=" + instrumentation +
                            '}';
                }
            }
        }

        /**
         * A strategy for determining if a native method name prefix should be used when rebasing methods.
         */
        protected interface NativeMethodStrategy {

            /**
             * Determines if this strategy enables name prefixing for native methods.
             *
             * @param instrumentation The instrumentation used.
             * @return {@code true} if this strategy indicates that a native method prefix should be used.
             */
            boolean isEnabled(Instrumentation instrumentation);

            /**
             * Resolves the method name transformer for this strategy.
             *
             * @return A method name transformer for this strategy.
             */
            MethodNameTransformer resolve();

            /**
             * Returns the method prefix if the strategy is enabled. This method must only be called if this strategy enables prefixing.
             *
             * @return The method prefix.
             */
            String getPrefix();

            /**
             * A native method strategy that suffixes method names with a random suffix and disables native method rebasement.
             */
            enum Disabled implements NativeMethodStrategy {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public MethodNameTransformer resolve() {
                    return MethodNameTransformer.Suffixing.withRandomSuffix();
                }

                @Override
                public boolean isEnabled(Instrumentation instrumentation) {
                    return false;
                }

                @Override
                public String getPrefix() {
                    throw new IllegalStateException("A disabled native method strategy does not define a method name prefix");
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.NativeMethodStrategy.Disabled." + name();
                }
            }

            /**
             * A native method strategy that prefixes method names with a fixed value for supporting rebasing of native methods.
             */
            class ForPrefix implements NativeMethodStrategy {

                /**
                 * The method name prefix.
                 */
                private final String prefix;

                /**
                 * Creates a new name prefixing native method strategy.
                 *
                 * @param prefix The method name prefix.
                 */
                protected ForPrefix(String prefix) {
                    this.prefix = prefix;
                }

                /**
                 * Creates a new native method strategy for prefixing method names.
                 *
                 * @param prefix The method name prefix.
                 * @return An appropriate native method strategy.
                 */
                protected static NativeMethodStrategy of(String prefix) {
                    if (prefix.length() == 0) {
                        throw new IllegalArgumentException("A method name prefix must not be the empty string");
                    }
                    return new ForPrefix(prefix);
                }

                @Override
                public MethodNameTransformer resolve() {
                    return new MethodNameTransformer.Prefixing(prefix);
                }

                @Override
                public boolean isEnabled(Instrumentation instrumentation) {
                    if (!instrumentation.isNativeMethodPrefixSupported()) {
                        throw new IllegalArgumentException("A prefix for native methods is not supported: " + instrumentation);
                    }
                    return true;
                }

                @Override
                public String getPrefix() {
                    return prefix;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass()) && prefix.equals(((ForPrefix) other).prefix);
                }

                @Override
                public int hashCode() {
                    return prefix.hashCode();
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.NativeMethodStrategy.ForPrefix{" +
                            "prefix='" + prefix + '\'' +
                            '}';
                }
            }
        }

        /**
         * A transformation serves as a handler for modifying a class.
         */
        protected interface Transformation {

            /**
             * Resolves an attempted transformation to a specific transformation.
             *
             * @param typeDescription     A description of the type that is to be transformed.
             * @param classLoader         The class loader of the type being transformed.
             * @param classBeingRedefined In case of a type redefinition, the loaded type being transformed or {@code null} if that is not the case.
             * @param protectionDomain    The protection domain of the type being transformed.
             * @param ignoredTypes        A matcher for types that are explicitly ignored.
             * @return A resolution for the given type.
             */
            Resolution resolve(TypeDescription typeDescription,
                               ClassLoader classLoader,
                               Class<?> classBeingRedefined,
                               ProtectionDomain protectionDomain,
                               ElementMatcher<? super TypeDescription> ignoredTypes);

            /**
             * A resolution to a transformation.
             */
            interface Resolution {

                /**
                 * Returns {@code true} if this resolution represents an actual type transformation. If this value is {@code false},
                 * this resolution will not attempt to transform a class.
                 *
                 * @return {@code true} if this resolution attempts to transform a type, {@code false} otherwise.
                 */
                boolean isResolved();

                /**
                 * Transforms a type or returns {@code null} if a type is not to be transformed.
                 *
                 * @param initializationStrategy     The initialization strategy to use.
                 * @param classFileLocator           The class file locator to use.
                 * @param typeStrategy               The definition handler to use.
                 * @param byteBuddy                  The Byte Buddy instance to use.
                 * @param methodNameTransformer      The method name transformer to be used.
                 * @param bootstrapInjectionStrategy The bootstrap injection strategy to be used.
                 * @param accessControlContext       The access control context to be used.
                 * @param listener                   The listener to be invoked to inform about an applied or non-applied transformation.
                 * @return The class file of the transformed class or {@code null} if no transformation is attempted.
                 */
                byte[] apply(InitializationStrategy initializationStrategy,
                             ClassFileLocator classFileLocator,
                             TypeStrategy typeStrategy,
                             ByteBuddy byteBuddy,
                             NativeMethodStrategy methodNameTransformer,
                             BootstrapInjectionStrategy bootstrapInjectionStrategy,
                             AccessControlContext accessControlContext,
                             Listener listener);

                /**
                 * A canonical implementation of a non-resolved resolution.
                 */
                class Unresolved implements Resolution {

                    /**
                     * The type that is not transformed.
                     */
                    private final TypeDescription typeDescription;

                    /**
                     * Creates a new unresolved resolution.
                     *
                     * @param typeDescription The type that is not transformed.
                     */
                    protected Unresolved(TypeDescription typeDescription) {
                        this.typeDescription = typeDescription;
                    }

                    @Override
                    public boolean isResolved() {
                        return false;
                    }

                    @Override
                    public byte[] apply(InitializationStrategy initializationStrategy,
                                        ClassFileLocator classFileLocator,
                                        TypeStrategy typeStrategy,
                                        ByteBuddy byteBuddy,
                                        NativeMethodStrategy methodNameTransformer,
                                        BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                        AccessControlContext accessControlContext,
                                        Listener listener) {
                        listener.onIgnored(typeDescription);
                        return NO_TRANSFORMATION;
                    }

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && typeDescription.equals(((Unresolved) other).typeDescription);
                    }

                    @Override
                    public int hashCode() {
                        return typeDescription.hashCode();
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.Default.Transformation.Resolution.Unresolved{" +
                                "typeDescription=" + typeDescription +
                                '}';
                    }
                }
            }

            /**
             * A transformation that does not attempt to transform any type.
             */
            enum Ignored implements Transformation {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public Resolution resolve(TypeDescription typeDescription,
                                          ClassLoader classLoader,
                                          Class<?> classBeingRedefined,
                                          ProtectionDomain protectionDomain, ElementMatcher<? super TypeDescription> ignoredTypes) {
                    return new Resolution.Unresolved(typeDescription);
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.Transformation.Ignored." + name();
                }
            }

            /**
             * A simple, active transformation.
             */
            class Simple implements Transformation {

                /**
                 * The raw matcher that is represented by this transformation.
                 */
                private final RawMatcher rawMatcher;

                /**
                 * The transformer that is represented by this transformation.
                 */
                private final Transformer transformer;

                /**
                 * Creates a new transformation.
                 *
                 * @param rawMatcher  The raw matcher that is represented by this transformation.
                 * @param transformer The transformer that is represented by this transformation.
                 */
                protected Simple(RawMatcher rawMatcher, Transformer transformer) {
                    this.rawMatcher = rawMatcher;
                    this.transformer = transformer;
                }

                @Override
                public Transformation.Resolution resolve(TypeDescription typeDescription,
                                                         ClassLoader classLoader,
                                                         Class<?> classBeingRedefined,
                                                         ProtectionDomain protectionDomain,
                                                         ElementMatcher<? super TypeDescription> ignoredTypes) {
                    return !ignoredTypes.matches(typeDescription) && rawMatcher.matches(typeDescription, classLoader, classBeingRedefined, protectionDomain)
                            ? new Resolution(typeDescription, classLoader, protectionDomain, transformer)
                            : new Transformation.Resolution.Unresolved(typeDescription);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && rawMatcher.equals(((Simple) other).rawMatcher)
                            && transformer.equals(((Simple) other).transformer);
                }

                @Override
                public int hashCode() {
                    int result = rawMatcher.hashCode();
                    result = 31 * result + transformer.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.Transformation.Simple{" +
                            "rawMatcher=" + rawMatcher +
                            ", transformer=" + transformer +
                            '}';
                }

                /**
                 * A resolution that performs a type transformation.
                 */
                protected static class Resolution implements Transformation.Resolution {

                    /**
                     * A description of the transformed type.
                     */
                    private final TypeDescription typeDescription;

                    /**
                     * The class loader of the transformed type.
                     */
                    private final ClassLoader classLoader;

                    /**
                     * The protection domain of the transformed type.
                     */
                    private final ProtectionDomain protectionDomain;

                    /**
                     * The transformer to be applied.
                     */
                    private final Transformer transformer;

                    /**
                     * Creates a new active transformation.
                     *
                     * @param typeDescription  A description of the transformed type.
                     * @param classLoader      The class loader of the transformed type.
                     * @param protectionDomain The protection domain of the transformed type.
                     * @param transformer      The transformer to be applied.
                     */
                    protected Resolution(TypeDescription typeDescription,
                                         ClassLoader classLoader,
                                         ProtectionDomain protectionDomain,
                                         Transformer transformer) {
                        this.typeDescription = typeDescription;
                        this.classLoader = classLoader;
                        this.protectionDomain = protectionDomain;
                        this.transformer = transformer;
                    }

                    @Override
                    public boolean isResolved() {
                        return true;
                    }

                    @Override
                    public byte[] apply(InitializationStrategy initializationStrategy,
                                        ClassFileLocator classFileLocator,
                                        TypeStrategy typeStrategy,
                                        ByteBuddy byteBuddy,
                                        NativeMethodStrategy methodNameTransformer,
                                        BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                        AccessControlContext accessControlContext,
                                        Listener listener) {
                        InitializationStrategy.Dispatcher dispatcher = initializationStrategy.dispatcher();
                        DynamicType.Unloaded<?> dynamicType = dispatcher.apply(transformer.transform(typeStrategy.builder(typeDescription,
                                byteBuddy,
                                classFileLocator,
                                methodNameTransformer.resolve()), typeDescription)).make();
                        dispatcher.register(dynamicType, classLoader, new BootstrapClassLoaderCapableInjectorFactory(bootstrapInjectionStrategy,
                                classLoader,
                                protectionDomain,
                                accessControlContext));
                        listener.onTransformation(typeDescription, dynamicType);
                        return dynamicType.getBytes();
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        Resolution that = (Resolution) other;
                        return typeDescription.equals(that.typeDescription)
                                && !(classLoader != null ? !classLoader.equals(that.classLoader) : that.classLoader != null)
                                && !(protectionDomain != null ? !protectionDomain.equals(that.protectionDomain) : that.protectionDomain != null)
                                && transformer.equals(that.transformer);
                    }

                    @Override
                    public int hashCode() {
                        int result = typeDescription.hashCode();
                        result = 31 * result + (classLoader != null ? classLoader.hashCode() : 0);
                        result = 31 * result + (protectionDomain != null ? protectionDomain.hashCode() : 0);
                        result = 31 * result + transformer.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.Default.Transformation.Simple.Resolution{" +
                                "typeDescription=" + typeDescription +
                                ", classLoader=" + classLoader +
                                ", protectionDomain=" + protectionDomain +
                                ", transformer=" + transformer +
                                '}';
                    }

                    /**
                     * An injector factory that resolves to a bootstrap class loader injection if this is necessary and enabled.
                     */
                    protected static class BootstrapClassLoaderCapableInjectorFactory implements InitializationStrategy.Dispatcher.InjectorFactory {

                        /**
                         * The bootstrap injection strategy being used.
                         */
                        private final BootstrapInjectionStrategy bootstrapInjectionStrategy;

                        /**
                         * The class loader for which to create an injection factory.
                         */
                        private final ClassLoader classLoader;

                        /**
                         * The protection domain of the created classes.
                         */
                        private final ProtectionDomain protectionDomain;

                        /**
                         * The access control context to be used.
                         */
                        private final AccessControlContext accessControlContext;

                        /**
                         * Creates a new bootstrap class loader capable injector factory.
                         *
                         * @param bootstrapInjectionStrategy The bootstrap injection strategy being used.
                         * @param classLoader                The class loader for which to create an injection factory.
                         * @param protectionDomain           The protection domain of the created classes.
                         * @param accessControlContext       The access control context to be used.
                         */
                        protected BootstrapClassLoaderCapableInjectorFactory(BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                                                             ClassLoader classLoader,
                                                                             ProtectionDomain protectionDomain,
                                                                             AccessControlContext accessControlContext) {
                            this.bootstrapInjectionStrategy = bootstrapInjectionStrategy;
                            this.classLoader = classLoader;
                            this.protectionDomain = protectionDomain;
                            this.accessControlContext = accessControlContext;
                        }

                        @Override
                        public ClassInjector resolve() {
                            return classLoader == null
                                    ? bootstrapInjectionStrategy.make(protectionDomain)
                                    : new ClassInjector.UsingReflection(classLoader, protectionDomain, accessControlContext);
                        }

                        @Override
                        public boolean equals(Object other) {
                            if (this == other) return true;
                            if (other == null || getClass() != other.getClass()) return false;
                            BootstrapClassLoaderCapableInjectorFactory that = (BootstrapClassLoaderCapableInjectorFactory) other;
                            return bootstrapInjectionStrategy.equals(that.bootstrapInjectionStrategy)
                                    && !(classLoader != null ? !classLoader.equals(that.classLoader) : that.classLoader != null)
                                    && !(protectionDomain != null ? !protectionDomain.equals(that.protectionDomain) : that.protectionDomain != null)
                                    && accessControlContext.equals(that.accessControlContext);
                        }

                        @Override
                        public int hashCode() {
                            int result = bootstrapInjectionStrategy.hashCode();
                            result = 31 * result + (protectionDomain != null ? protectionDomain.hashCode() : 0);
                            result = 31 * result + (classLoader != null ? classLoader.hashCode() : 0);
                            result = 31 * result + accessControlContext.hashCode();
                            return result;
                        }

                        @Override
                        public String toString() {
                            return "AgentBuilder.Default.Transformation.Simple.Resolution.BootstrapClassLoaderCapableInjectorFactory{" +
                                    "bootstrapInjectionStrategy=" + bootstrapInjectionStrategy +
                                    ", classLoader=" + classLoader +
                                    ", protectionDomain=" + protectionDomain +
                                    ", accessControlContext=" + accessControlContext +
                                    '}';
                        }
                    }
                }
            }

            /**
             * A compound transformation that applied several transformation in the given order and applies the first active transformation.
             */
            class Compound implements Transformation {

                /**
                 * The list of transformations to apply in their application order.
                 */
                private final List<? extends Transformation> transformations;

                /**
                 * Creates a new compound transformation.
                 *
                 * @param transformation An array of transformations to apply in their application order.
                 */
                protected Compound(Transformation... transformation) {
                    this(Arrays.asList(transformation));
                }

                /**
                 * Creates a new compound transformation.
                 *
                 * @param transformations A list of transformations to apply in their application order.
                 */
                protected Compound(List<? extends Transformation> transformations) {
                    this.transformations = transformations;
                }

                @Override
                public Resolution resolve(TypeDescription typeDescription,
                                          ClassLoader classLoader,
                                          Class<?> classBeingRedefined,
                                          ProtectionDomain protectionDomain,
                                          ElementMatcher<? super TypeDescription> ignoredTypes) {
                    for (Transformation transformation : transformations) {
                        Resolution resolution = transformation.resolve(typeDescription, classLoader, classBeingRedefined, protectionDomain, ignoredTypes);
                        if (resolution.isResolved()) {
                            return resolution;
                        }
                    }
                    return new Resolution.Unresolved(typeDescription);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && transformations.equals(((Compound) other).transformations);
                }

                @Override
                public int hashCode() {
                    return transformations.hashCode();
                }

                @Override
                public String toString() {
                    return "AgentBuilder.Default.Transformation.Compound{" +
                            "transformations=" + transformations +
                            '}';
                }
            }
        }

        /**
         * A {@link java.lang.instrument.ClassFileTransformer} that implements the enclosing agent builder's
         * configuration.
         */
        protected static class ExecutingTransformer implements ClassFileTransformer {

            /**
             * The Byte Buddy instance to be used.
             */
            private final ByteBuddy byteBuddy;

            /**
             * The binary locator to use.
             */
            private final BinaryLocator binaryLocator;

            /**
             * The definition handler to use.
             */
            private final TypeStrategy typeStrategy;

            /**
             * The listener to notify on transformations.
             */
            private final Listener listener;

            /**
             * The native method strategy to apply.
             */
            private final NativeMethodStrategy nativeMethodStrategy;

            /**
             * The access control context to use for loading classes.
             */
            private final AccessControlContext accessControlContext;

            /**
             * The initialization strategy to use for transformed types.
             */
            private final InitializationStrategy initializationStrategy;

            /**
             * The injection strategy for injecting classes into the bootstrap class loader.
             */
            private final BootstrapInjectionStrategy bootstrapInjectionStrategy;

            /**
             * A matcher that indicates any type that should be excluded from instrumentation.
             */
            private final ElementMatcher<? super TypeDescription> ignoredTypes;

            /**
             * The transformation object for handling type transformations.
             */
            private final Transformation transformation;

            /**
             * Creates a new class file transformer.
             *
             * @param byteBuddy                  The Byte Buddy instance to be used.
             * @param binaryLocator              The binary locator to use.
             * @param typeStrategy               The definition handler to use.
             * @param listener                   The listener to notify on transformations.
             * @param nativeMethodStrategy       The native method strategy to apply.
             * @param accessControlContext       The access control context to use for loading classes.
             * @param initializationStrategy     The initialization strategy to use for transformed types.
             * @param bootstrapInjectionStrategy The injection strategy for injecting classes into the bootstrap class loader.
             * @param ignoredTypes               A matcher that indicates any type that should be excluded from instrumentation.
             * @param transformation             The transformation object for handling type transformations.
             */
            public ExecutingTransformer(ByteBuddy byteBuddy,
                                        BinaryLocator binaryLocator,
                                        TypeStrategy typeStrategy,
                                        Listener listener,
                                        NativeMethodStrategy nativeMethodStrategy,
                                        AccessControlContext accessControlContext,
                                        InitializationStrategy initializationStrategy,
                                        BootstrapInjectionStrategy bootstrapInjectionStrategy,
                                        ElementMatcher<? super TypeDescription> ignoredTypes,
                                        Transformation transformation) {
                this.byteBuddy = byteBuddy;
                this.binaryLocator = binaryLocator;
                this.typeStrategy = typeStrategy;
                this.listener = listener;
                this.nativeMethodStrategy = nativeMethodStrategy;
                this.accessControlContext = accessControlContext;
                this.initializationStrategy = initializationStrategy;
                this.bootstrapInjectionStrategy = bootstrapInjectionStrategy;
                this.ignoredTypes = ignoredTypes;
                this.transformation = transformation;
            }

            @Override
            public byte[] transform(ClassLoader classLoader,
                                    String internalTypeName,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] binaryRepresentation) {
                if (internalTypeName == null) {
                    return NO_TRANSFORMATION;
                }
                String binaryTypeName = internalTypeName.replace('/', '.');
                try {
                    ClassFileLocator classFileLocator = ClassFileLocator.Simple.of(binaryTypeName,
                            binaryRepresentation,
                            binaryLocator.classFileLocator(classLoader));
                    return transformation.resolve(classBeingRedefined == null
                                    ? binaryLocator.typePool(classFileLocator, classLoader).describe(binaryTypeName).resolve()
                                    : new TypeDescription.ForLoadedType(classBeingRedefined),
                            classLoader,
                            classBeingRedefined,
                            protectionDomain,
                            ignoredTypes).apply(initializationStrategy,
                            classFileLocator,
                            typeStrategy,
                            byteBuddy,
                            nativeMethodStrategy,
                            bootstrapInjectionStrategy,
                            accessControlContext,
                            listener);
                } catch (Throwable throwable) {
                    listener.onError(binaryTypeName, throwable);
                    return NO_TRANSFORMATION;
                } finally {
                    listener.onComplete(binaryTypeName);
                }
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                ExecutingTransformer that = (ExecutingTransformer) other;
                return byteBuddy.equals(that.byteBuddy)
                        && binaryLocator.equals(that.binaryLocator)
                        && typeStrategy.equals(that.typeStrategy)
                        && initializationStrategy.equals(that.initializationStrategy)
                        && listener.equals(that.listener)
                        && nativeMethodStrategy.equals(that.nativeMethodStrategy)
                        && bootstrapInjectionStrategy.equals(that.bootstrapInjectionStrategy)
                        && accessControlContext.equals(that.accessControlContext)
                        && ignoredTypes.equals(that.ignoredTypes)
                        && transformation.equals(that.transformation);
            }

            @Override
            public int hashCode() {
                int result = byteBuddy.hashCode();
                result = 31 * result + binaryLocator.hashCode();
                result = 31 * result + typeStrategy.hashCode();
                result = 31 * result + initializationStrategy.hashCode();
                result = 31 * result + listener.hashCode();
                result = 31 * result + nativeMethodStrategy.hashCode();
                result = 31 * result + bootstrapInjectionStrategy.hashCode();
                result = 31 * result + accessControlContext.hashCode();
                result = 31 * result + ignoredTypes.hashCode();
                result = 31 * result + transformation.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "AgentBuilder.Default.ExecutingTransformer{" +
                        "byteBuddy=" + byteBuddy +
                        ", binaryLocator=" + binaryLocator +
                        ", typeStrategy=" + typeStrategy +
                        ", initializationStrategy=" + initializationStrategy +
                        ", listener=" + listener +
                        ", nativeMethodStrategy=" + nativeMethodStrategy +
                        ", bootstrapInjectionStrategy=" + bootstrapInjectionStrategy +
                        ", accessControlContext=" + accessControlContext +
                        ", ignoredTypes=" + ignoredTypes +
                        ", transformation=" + transformation +
                        '}';
            }
        }

        /**
         * A helper class that describes a {@link net.bytebuddy.agent.builder.AgentBuilder.Default} after supplying
         * a {@link net.bytebuddy.agent.builder.AgentBuilder.RawMatcher} such that one or several
         * {@link net.bytebuddy.agent.builder.AgentBuilder.Transformer}s can be supplied.
         */
        protected class Matched implements Identified.Extendable {

            /**
             * The supplied raw matcher.
             */
            private final RawMatcher rawMatcher;

            /**
             * The supplied transformer.
             */
            private final Transformer transformer;

            /**
             * Creates a new matched default agent builder.
             *
             * @param rawMatcher  The supplied raw matcher.
             * @param transformer The supplied transformer.
             */
            protected Matched(RawMatcher rawMatcher, Transformer transformer) {
                this.rawMatcher = rawMatcher;
                this.transformer = transformer;
            }

            @Override
            public Identified.Extendable transform(Transformer transformer) {
                return new Matched(rawMatcher, new Transformer.Compound(this.transformer, transformer));
            }

            @Override
            public Identified type(RawMatcher matcher) {
                return materialize().type(matcher);
            }

            @Override
            public Identified type(ElementMatcher<? super TypeDescription> typeMatcher) {
                return materialize().type(typeMatcher);
            }

            @Override
            public Identified type(ElementMatcher<? super TypeDescription> typeMatcher, ElementMatcher<? super ClassLoader> classLoaderMatcher) {
                return materialize().type(typeMatcher, classLoaderMatcher);
            }

            @Override
            public AgentBuilder with(ByteBuddy byteBuddy) {
                return materialize().with(byteBuddy);
            }

            @Override
            public AgentBuilder with(Listener listener) {
                return materialize().with(listener);
            }

            @Override
            public AgentBuilder with(TypeStrategy typeStrategy) {
                return materialize().with(typeStrategy);
            }

            @Override
            public AgentBuilder with(BinaryLocator binaryLocator) {
                return materialize().with(binaryLocator);
            }

            @Override
            public AgentBuilder with(AccessControlContext accessControlContext) {
                return materialize().with(accessControlContext);
            }

            @Override
            public AgentBuilder with(InitializationStrategy initializationStrategy) {
                return materialize().with(initializationStrategy);
            }

            @Override
            public AgentBuilder with(RedefinitionStrategy redefinitionStrategy) {
                return materialize().with(redefinitionStrategy);
            }

            @Override
            public AgentBuilder with(LambdaInstrumentationStrategy lambdaInstrumentationStrategy) {
                return materialize().with(lambdaInstrumentationStrategy);
            }

            @Override
            public AgentBuilder enableBootstrapInjection(File folder, Instrumentation instrumentation) {
                return materialize().enableBootstrapInjection(folder, instrumentation);
            }

            @Override
            public AgentBuilder disableBootstrapInjection() {
                return materialize().disableBootstrapInjection();
            }

            @Override
            public AgentBuilder enableNativeMethodPrefix(String prefix) {
                return materialize().enableNativeMethodPrefix(prefix);
            }

            @Override
            public AgentBuilder disableNativeMethodPrefix() {
                return materialize().disableNativeMethodPrefix();
            }

            @Override
            public AgentBuilder ignore(ElementMatcher<? super TypeDescription> ignoredTypes) {
                return materialize().ignore(ignoredTypes);
            }

            @Override
            public ClassFileTransformer makeRaw() {
                return materialize().makeRaw();
            }

            @Override
            public ClassFileTransformer installOn(Instrumentation instrumentation) {
                return materialize().installOn(instrumentation);
            }

            @Override
            public ClassFileTransformer installOnByteBuddyAgent() {
                return materialize().installOnByteBuddyAgent();
            }

            /**
             * Materializes the currently described {@link net.bytebuddy.agent.builder.AgentBuilder.Default.Transformation}.
             *
             * @return An agent builder that represents the currently described entry of this instance.
             */
            protected AgentBuilder materialize() {
                return new Default(byteBuddy,
                        binaryLocator,
                        typeStrategy,
                        listener,
                        nativeMethodStrategy,
                        accessControlContext,
                        initializationStrategy,
                        redefinitionStrategy,
                        bootstrapInjectionStrategy,
                        lambdaInstrumentationStrategy,
                        ignoredTypes,
                        new Transformation.Compound(new Transformation.Simple(rawMatcher, transformer), transformation));
            }

            /**
             * Returns the outer instance.
             *
             * @return The outer instance.
             */
            private Default getOuter() {
                return Default.this;
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && rawMatcher.equals(((Matched) other).rawMatcher)
                        && transformer.equals(((Matched) other).transformer)
                        && Default.this.equals(((Matched) other).getOuter());
            }

            @Override
            public int hashCode() {
                int result = rawMatcher.hashCode();
                result = 31 * result + transformer.hashCode();
                result = 31 * result + Default.this.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "AgentBuilder.Default.Matched{" +
                        "rawMatcher=" + rawMatcher +
                        ", transformer=" + transformer +
                        ", agentBuilder=" + Default.this +
                        '}';
            }
        }
    }
}
