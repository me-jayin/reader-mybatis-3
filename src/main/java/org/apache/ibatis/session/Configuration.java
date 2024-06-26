/*
 *    Copyright 2009-2024 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.session;

import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.builder.annotation.MethodResolver;
import org.apache.ibatis.builder.xml.XMLStatementBuilder;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.FifoCache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.decorators.SoftCache;
import org.apache.ibatis.cache.decorators.WeakCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.datasource.jndi.JndiDataSourceFactory;
import org.apache.ibatis.datasource.pooled.PooledDataSourceFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSourceFactory;
import org.apache.ibatis.executor.*;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.executor.loader.cglib.CglibProxyFactory;
import org.apache.ibatis.executor.loader.javassist.JavassistProxyFactory;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl;
import org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl;
import org.apache.ibatis.logging.log4j.Log4jImpl;
import org.apache.ibatis.logging.log4j2.Log4j2Impl;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.InterceptorChain;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.scripting.LanguageDriverRegistry;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * @author Clinton Begin
 */
public class Configuration {

    protected Environment environment;

    protected boolean safeRowBoundsEnabled;
    protected boolean safeResultHandlerEnabled = true;
    protected boolean mapUnderscoreToCamelCase;
    protected boolean aggressiveLazyLoading;
    protected boolean multipleResultSetsEnabled = true;
    protected boolean useGeneratedKeys;
    protected boolean useColumnLabel = true;
    protected boolean cacheEnabled = true;
    protected boolean callSettersOnNulls;
    protected boolean useActualParamName = true;
    protected boolean returnInstanceForEmptyRow;
    protected boolean shrinkWhitespacesInSql;
    protected boolean nullableOnForEach;
    protected boolean argNameBasedConstructorAutoMapping;

    protected String logPrefix;
    protected Class<? extends Log> logImpl;
    protected Class<? extends VFS> vfsImpl;
    protected Class<?> defaultSqlProviderType;
    protected LocalCacheScope localCacheScope = LocalCacheScope.SESSION;
    protected JdbcType jdbcTypeForNull = JdbcType.OTHER;
    protected Set<String> lazyLoadTriggerMethods = new HashSet<>(
            Arrays.asList("equals", "clone", "hashCode", "toString"));
    protected Integer defaultStatementTimeout;
    protected Integer defaultFetchSize;
    protected ResultSetType defaultResultSetType;
    protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;
    protected AutoMappingBehavior autoMappingBehavior = AutoMappingBehavior.PARTIAL;
    protected AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior = AutoMappingUnknownColumnBehavior.NONE;

    protected Properties variables = new Properties();
    protected ReflectorFactory reflectorFactory = new DefaultReflectorFactory();
    protected ObjectFactory objectFactory = new DefaultObjectFactory();
    protected ObjectWrapperFactory objectWrapperFactory = new DefaultObjectWrapperFactory();

    protected boolean lazyLoadingEnabled;
    protected ProxyFactory proxyFactory = new JavassistProxyFactory(); // #224 Using internal Javassist instead of OGNL

    protected String databaseId;
    /**
     * Configuration factory class. Used to create Configuration for loading deserialized unread properties.
     *
     * @see <a href='https://github.com/mybatis/old-google-code-issues/issues/300'>Issue 300 (google code)</a>
     */
    protected Class<?> configurationFactory;

    /**
     * mapper的注册器，提供mapper注册和获取已注册的mapper
     */
    protected final MapperRegistry mapperRegistry = new MapperRegistry(this);
    /**
     * 拦接器链，内部持有拦截器List，并提供添加方法，配置中的拦截器将被注册到该链中
     * org.apache.ibatis.session.Configuration#addInterceptor
     */
    protected final InterceptorChain interceptorChain = new InterceptorChain();
    /**
     * 类型处理器注册器，默认会注册预制的类型处理器，注册/获取时分为两种注册角度：java类型、jdbc类型
     */
    protected final TypeHandlerRegistry typeHandlerRegistry = new TypeHandlerRegistry(this);
    /**
     * 别名注册器，默认会注册预制的类型，并提供类型注册、及根据类型获取对应类（是别名则获取别名对应类，不是别名则直接加载类）的方法
     */
    protected final TypeAliasRegistry typeAliasRegistry = new TypeAliasRegistry();
    /**
     * 语言驱动注册器，提供注册语言驱动和根据类型获取语言驱动器的方法
     */
    protected final LanguageDriverRegistry languageRegistry = new LanguageDriverRegistry();

    protected final Map<String, MappedStatement> mappedStatements = new StrictMap<MappedStatement>(
            "Mapped Statements collection")
            .conflictMessageProducer((savedValue, targetValue) -> ". please check " + savedValue.getResource() + " and "
                    + targetValue.getResource());
    protected final Map<String, Cache> caches = new StrictMap<>("Caches collection");
    protected final Map<String, ResultMap> resultMaps = new StrictMap<>("Result Maps collection");
    protected final Map<String, ParameterMap> parameterMaps = new StrictMap<>("Parameter Maps collection");
    protected final Map<String, KeyGenerator> keyGenerators = new StrictMap<>("Key Generators collection");

    /**
     * 储存已加载的资源
     */
    protected final Set<String> loadedResources = new HashSet<>();
    protected final Map<String, XNode> sqlFragments = new StrictMap<>("XML fragments parsed from previous mappers");
    /////// 以下部分是由于 mybatis 支持嵌套引用，所以在首次加载遇到未加载的嵌套引用时，
    /////// 会使用缓存的方式，来确保嵌套引用的加载正确性
    /**
     * 未初始化完成的 sql语句
     */
    protected final Collection<XMLStatementBuilder> incompleteStatements = new LinkedList<>();
    /**
     * 未初始化完成的 缓存引用对象
     */
    protected final Collection<CacheRefResolver> incompleteCacheRefs = new LinkedList<>();
    /**
     * 未初始化完成的 resultMap
     */
    protected final Collection<ResultMapResolver> incompleteResultMaps = new LinkedList<>();
    /**
     * 未初始化完成的 method
     */
    protected final Collection<MethodResolver> incompleteMethods = new LinkedList<>();

    private final ReentrantLock incompleteResultMapsLock = new ReentrantLock();
    private final ReentrantLock incompleteCacheRefsLock = new ReentrantLock();
    private final ReentrantLock incompleteStatementsLock = new ReentrantLock();
    private final ReentrantLock incompleteMethodsLock = new ReentrantLock();

    /*
     * A map holds cache-ref relationship. The key is the namespace that references a cache bound to another namespace and
     * the value is the namespace which the actual cache is bound to.
     */
    /**
     * 用于记录缓存引用关系，key为当前命名空间，value是被引用的命名空间
     */
    protected final Map<String, String> cacheRefMap = new HashMap<>();

    public Configuration(Environment environment) {
        this();
        this.environment = environment;
    }

    public Configuration() {
        // 注册预制的 TypeHandler
        typeAliasRegistry.registerAlias("JDBC", JdbcTransactionFactory.class);
        typeAliasRegistry.registerAlias("MANAGED", ManagedTransactionFactory.class);

        typeAliasRegistry.registerAlias("JNDI", JndiDataSourceFactory.class);
        typeAliasRegistry.registerAlias("POOLED", PooledDataSourceFactory.class);
        typeAliasRegistry.registerAlias("UNPOOLED", UnpooledDataSourceFactory.class);

        typeAliasRegistry.registerAlias("PERPETUAL", PerpetualCache.class);
        typeAliasRegistry.registerAlias("FIFO", FifoCache.class);
        typeAliasRegistry.registerAlias("LRU", LruCache.class);
        typeAliasRegistry.registerAlias("SOFT", SoftCache.class);
        typeAliasRegistry.registerAlias("WEAK", WeakCache.class);

        typeAliasRegistry.registerAlias("DB_VENDOR", VendorDatabaseIdProvider.class);

        typeAliasRegistry.registerAlias("XML", XMLLanguageDriver.class);
        typeAliasRegistry.registerAlias("RAW", RawLanguageDriver.class);

        typeAliasRegistry.registerAlias("SLF4J", Slf4jImpl.class);
        typeAliasRegistry.registerAlias("COMMONS_LOGGING", JakartaCommonsLoggingImpl.class);
        typeAliasRegistry.registerAlias("LOG4J", Log4jImpl.class);
        typeAliasRegistry.registerAlias("LOG4J2", Log4j2Impl.class);
        typeAliasRegistry.registerAlias("JDK_LOGGING", Jdk14LoggingImpl.class);
        typeAliasRegistry.registerAlias("STDOUT_LOGGING", StdOutImpl.class);
        typeAliasRegistry.registerAlias("NO_LOGGING", NoLoggingImpl.class);

        typeAliasRegistry.registerAlias("CGLIB", CglibProxyFactory.class);
        typeAliasRegistry.registerAlias("JAVASSIST", JavassistProxyFactory.class);

        // 设置默认的语言驱动，并且额外注册一个 RawLanguageDriver 的语言驱动
        languageRegistry.setDefaultDriverClass(XMLLanguageDriver.class);
        languageRegistry.register(RawLanguageDriver.class);
    }

    public String getLogPrefix() {
        return logPrefix;
    }

    public void setLogPrefix(String logPrefix) {
        this.logPrefix = logPrefix;
    }

    public Class<? extends Log> getLogImpl() {
        return logImpl;
    }

    public void setLogImpl(Class<? extends Log> logImpl) {
        if (logImpl != null) {
            this.logImpl = logImpl;
            LogFactory.useCustomLogging(this.logImpl);
        }
    }

    public Class<? extends VFS> getVfsImpl() {
        return this.vfsImpl;
    }

    public void setVfsImpl(Class<? extends VFS> vfsImpl) {
        if (vfsImpl != null) {
            this.vfsImpl = vfsImpl;
            VFS.addImplClass(this.vfsImpl);
        }
    }

    /**
     * Gets an applying type when omit a type on sql provider annotation(e.g.
     * {@link org.apache.ibatis.annotations.SelectProvider}).
     *
     * @return the default type for sql provider annotation
     * @since 3.5.6
     */
    public Class<?> getDefaultSqlProviderType() {
        return defaultSqlProviderType;
    }

    /**
     * Sets an applying type when omit a type on sql provider annotation(e.g.
     * {@link org.apache.ibatis.annotations.SelectProvider}).
     *
     * @param defaultSqlProviderType the default type for sql provider annotation
     * @since 3.5.6
     */
    public void setDefaultSqlProviderType(Class<?> defaultSqlProviderType) {
        this.defaultSqlProviderType = defaultSqlProviderType;
    }

    public boolean isCallSettersOnNulls() {
        return callSettersOnNulls;
    }

    public void setCallSettersOnNulls(boolean callSettersOnNulls) {
        this.callSettersOnNulls = callSettersOnNulls;
    }

    public boolean isUseActualParamName() {
        return useActualParamName;
    }

    public void setUseActualParamName(boolean useActualParamName) {
        this.useActualParamName = useActualParamName;
    }

    public boolean isReturnInstanceForEmptyRow() {
        return returnInstanceForEmptyRow;
    }

    public void setReturnInstanceForEmptyRow(boolean returnEmptyInstance) {
        this.returnInstanceForEmptyRow = returnEmptyInstance;
    }

    public boolean isShrinkWhitespacesInSql() {
        return shrinkWhitespacesInSql;
    }

    public void setShrinkWhitespacesInSql(boolean shrinkWhitespacesInSql) {
        this.shrinkWhitespacesInSql = shrinkWhitespacesInSql;
    }

    /**
     * Sets the default value of 'nullable' attribute on 'foreach' tag.
     *
     * @param nullableOnForEach If nullable, set to {@code true}
     * @since 3.5.9
     */
    public void setNullableOnForEach(boolean nullableOnForEach) {
        this.nullableOnForEach = nullableOnForEach;
    }

    /**
     * Returns the default value of 'nullable' attribute on 'foreach' tag.
     * <p>
     * Default is {@code false}.
     *
     * @return If nullable, set to {@code true}
     * @since 3.5.9
     */
    public boolean isNullableOnForEach() {
        return nullableOnForEach;
    }

    public boolean isArgNameBasedConstructorAutoMapping() {
        return argNameBasedConstructorAutoMapping;
    }

    public void setArgNameBasedConstructorAutoMapping(boolean argNameBasedConstructorAutoMapping) {
        this.argNameBasedConstructorAutoMapping = argNameBasedConstructorAutoMapping;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }

    public Class<?> getConfigurationFactory() {
        return configurationFactory;
    }

    public void setConfigurationFactory(Class<?> configurationFactory) {
        this.configurationFactory = configurationFactory;
    }

    public boolean isSafeResultHandlerEnabled() {
        return safeResultHandlerEnabled;
    }

    public void setSafeResultHandlerEnabled(boolean safeResultHandlerEnabled) {
        this.safeResultHandlerEnabled = safeResultHandlerEnabled;
    }

    public boolean isSafeRowBoundsEnabled() {
        return safeRowBoundsEnabled;
    }

    public void setSafeRowBoundsEnabled(boolean safeRowBoundsEnabled) {
        this.safeRowBoundsEnabled = safeRowBoundsEnabled;
    }

    public boolean isMapUnderscoreToCamelCase() {
        return mapUnderscoreToCamelCase;
    }

    public void setMapUnderscoreToCamelCase(boolean mapUnderscoreToCamelCase) {
        this.mapUnderscoreToCamelCase = mapUnderscoreToCamelCase;
    }

    /**
     * 添加已加载的资源
     */
    public void addLoadedResource(String resource) {
        loadedResources.add(resource);
    }

    /**
     * 判断资源是否加载
     */
    public boolean isResourceLoaded(String resource) {
        return loadedResources.contains(resource);
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public AutoMappingBehavior getAutoMappingBehavior() {
        return autoMappingBehavior;
    }

    public void setAutoMappingBehavior(AutoMappingBehavior autoMappingBehavior) {
        this.autoMappingBehavior = autoMappingBehavior;
    }

    /**
     * Gets the auto mapping unknown column behavior.
     *
     * @return the auto mapping unknown column behavior
     * @since 3.4.0
     */
    public AutoMappingUnknownColumnBehavior getAutoMappingUnknownColumnBehavior() {
        return autoMappingUnknownColumnBehavior;
    }

    /**
     * Sets the auto mapping unknown column behavior.
     *
     * @param autoMappingUnknownColumnBehavior the new auto mapping unknown column behavior
     * @since 3.4.0
     */
    public void setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior) {
        this.autoMappingUnknownColumnBehavior = autoMappingUnknownColumnBehavior;
    }

    public boolean isLazyLoadingEnabled() {
        return lazyLoadingEnabled;
    }

    public void setLazyLoadingEnabled(boolean lazyLoadingEnabled) {
        this.lazyLoadingEnabled = lazyLoadingEnabled;
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        if (proxyFactory == null) {
            proxyFactory = new JavassistProxyFactory();
        }
        this.proxyFactory = proxyFactory;
    }

    public boolean isAggressiveLazyLoading() {
        return aggressiveLazyLoading;
    }

    public void setAggressiveLazyLoading(boolean aggressiveLazyLoading) {
        this.aggressiveLazyLoading = aggressiveLazyLoading;
    }

    public boolean isMultipleResultSetsEnabled() {
        return multipleResultSetsEnabled;
    }

    public void setMultipleResultSetsEnabled(boolean multipleResultSetsEnabled) {
        this.multipleResultSetsEnabled = multipleResultSetsEnabled;
    }

    public Set<String> getLazyLoadTriggerMethods() {
        return lazyLoadTriggerMethods;
    }

    public void setLazyLoadTriggerMethods(Set<String> lazyLoadTriggerMethods) {
        this.lazyLoadTriggerMethods = lazyLoadTriggerMethods;
    }

    public boolean isUseGeneratedKeys() {
        return useGeneratedKeys;
    }

    public void setUseGeneratedKeys(boolean useGeneratedKeys) {
        this.useGeneratedKeys = useGeneratedKeys;
    }

    public ExecutorType getDefaultExecutorType() {
        return defaultExecutorType;
    }

    public void setDefaultExecutorType(ExecutorType defaultExecutorType) {
        this.defaultExecutorType = defaultExecutorType;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public Integer getDefaultStatementTimeout() {
        return defaultStatementTimeout;
    }

    public void setDefaultStatementTimeout(Integer defaultStatementTimeout) {
        this.defaultStatementTimeout = defaultStatementTimeout;
    }

    /**
     * Gets the default fetch size.
     *
     * @return the default fetch size
     * @since 3.3.0
     */
    public Integer getDefaultFetchSize() {
        return defaultFetchSize;
    }

    /**
     * Sets the default fetch size.
     *
     * @param defaultFetchSize the new default fetch size
     * @since 3.3.0
     */
    public void setDefaultFetchSize(Integer defaultFetchSize) {
        this.defaultFetchSize = defaultFetchSize;
    }

    /**
     * Gets the default result set type.
     *
     * @return the default result set type
     * @since 3.5.2
     */
    public ResultSetType getDefaultResultSetType() {
        return defaultResultSetType;
    }

    /**
     * Sets the default result set type.
     *
     * @param defaultResultSetType the new default result set type
     * @since 3.5.2
     */
    public void setDefaultResultSetType(ResultSetType defaultResultSetType) {
        this.defaultResultSetType = defaultResultSetType;
    }

    public boolean isUseColumnLabel() {
        return useColumnLabel;
    }

    public void setUseColumnLabel(boolean useColumnLabel) {
        this.useColumnLabel = useColumnLabel;
    }

    public LocalCacheScope getLocalCacheScope() {
        return localCacheScope;
    }

    public void setLocalCacheScope(LocalCacheScope localCacheScope) {
        this.localCacheScope = localCacheScope;
    }

    public JdbcType getJdbcTypeForNull() {
        return jdbcTypeForNull;
    }

    public void setJdbcTypeForNull(JdbcType jdbcTypeForNull) {
        this.jdbcTypeForNull = jdbcTypeForNull;
    }

    public Properties getVariables() {
        return variables;
    }

    public void setVariables(Properties variables) {
        this.variables = variables;
    }

    public TypeHandlerRegistry getTypeHandlerRegistry() {
        return typeHandlerRegistry;
    }

    /**
     * Set a default {@link TypeHandler} class for {@link Enum}. A default {@link TypeHandler} is
     * {@link org.apache.ibatis.type.EnumTypeHandler}.
     *
     * @param typeHandler a type handler class for {@link Enum}
     * @since 3.4.5
     */
    public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
        if (typeHandler != null) {
            getTypeHandlerRegistry().setDefaultEnumTypeHandler(typeHandler);
        }
    }

    public TypeAliasRegistry getTypeAliasRegistry() {
        return typeAliasRegistry;
    }

    /**
     * Gets the mapper registry.
     *
     * @return the mapper registry
     * @since 3.2.2
     */
    public MapperRegistry getMapperRegistry() {
        return mapperRegistry;
    }

    public ReflectorFactory getReflectorFactory() {
        return reflectorFactory;
    }

    public void setReflectorFactory(ReflectorFactory reflectorFactory) {
        this.reflectorFactory = reflectorFactory;
    }

    public ObjectFactory getObjectFactory() {
        return objectFactory;
    }

    public void setObjectFactory(ObjectFactory objectFactory) {
        this.objectFactory = objectFactory;
    }

    public ObjectWrapperFactory getObjectWrapperFactory() {
        return objectWrapperFactory;
    }

    public void setObjectWrapperFactory(ObjectWrapperFactory objectWrapperFactory) {
        this.objectWrapperFactory = objectWrapperFactory;
    }

    /**
     * Gets the interceptors.
     *
     * @return the interceptors
     * @since 3.2.2
     */
    public List<Interceptor> getInterceptors() {
        return interceptorChain.getInterceptors();
    }

    public LanguageDriverRegistry getLanguageRegistry() {
        return languageRegistry;
    }

    public void setDefaultScriptingLanguage(Class<? extends LanguageDriver> driver) {
        if (driver == null) {
            driver = XMLLanguageDriver.class;
        }
        getLanguageRegistry().setDefaultDriverClass(driver);
    }

    public LanguageDriver getDefaultScriptingLanguageInstance() {
        return languageRegistry.getDefaultDriver();
    }

    /**
     * Gets the language driver.
     *
     * @param langClass the lang class
     * @return the language driver
     * @since 3.5.1
     */
    public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
        if (langClass == null) {
            return languageRegistry.getDefaultDriver();
        }
        languageRegistry.register(langClass);
        return languageRegistry.getDriver(langClass);
    }

    /**
     * Gets the default scripting language instance.
     *
     * @return the default scripting language instance
     * @deprecated Use {@link #getDefaultScriptingLanguageInstance()}
     */
    @Deprecated
    public LanguageDriver getDefaultScriptingLanuageInstance() {
        return getDefaultScriptingLanguageInstance();
    }

    public MetaObject newMetaObject(Object object) {
        return MetaObject.forObject(object, objectFactory, objectWrapperFactory, reflectorFactory);
    }

    /**
     * 创建参数处理器，并对参数处理器进行拦截器增强
     * @param mappedStatement
     * @param parameterObject
     * @param boundSql
     * @return
     */
    public ParameterHandler newParameterHandler(MappedStatement mappedStatement, Object parameterObject,
                                                BoundSql boundSql) {
        ParameterHandler parameterHandler = mappedStatement.getLang().createParameterHandler(mappedStatement,
                parameterObject, boundSql);
        return (ParameterHandler) interceptorChain.pluginAll(parameterHandler);
    }

    /**
     * 创建结果集处理器，并进行拦截器增强
     * @param executor
     * @param mappedStatement
     * @param rowBounds
     * @param parameterHandler
     * @param resultHandler
     * @param boundSql
     * @return
     */
    public ResultSetHandler newResultSetHandler(Executor executor, MappedStatement mappedStatement, RowBounds rowBounds,
                                                ParameterHandler parameterHandler, ResultHandler resultHandler, BoundSql boundSql) {
        ResultSetHandler resultSetHandler = new DefaultResultSetHandler(executor, mappedStatement, parameterHandler,
                resultHandler, boundSql, rowBounds);
        return (ResultSetHandler) interceptorChain.pluginAll(resultSetHandler);
    }

    /**
     * 创建一个 StatementHandler，用于构建 PreparedStatement 对象
     * @param executor
     * @param mappedStatement
     * @param parameterObject
     * @param rowBounds
     * @param resultHandler
     * @param boundSql BoundSql，即持有 SQL、请求参数
     * @return
     */
    public StatementHandler newStatementHandler(Executor executor, MappedStatement mappedStatement,
                                                Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        // 构建一个 RoutingStatementHandler 路由对象，内部实际上会持有一个 委托对象，而委托对象才是最终 StatementHandler，有以下类型：
        // 1. SimpleStatementHandler：简单的
        // 2. PreparedStatementHandler：预处理
        // 3. CallableStatementHandler：可回调
        StatementHandler statementHandler = new RoutingStatementHandler(executor, mappedStatement, parameterObject,
                rowBounds, resultHandler, boundSql);
        // 进行 Interceptor 装配，生成最终代理后的 StatementHandler
        return (StatementHandler) interceptorChain.pluginAll(statementHandler);
    }

    public Executor newExecutor(Transaction transaction) {
        return newExecutor(transaction, defaultExecutorType);
    }

    /**
     * 创建执行器，创建时会有以下操作
     * 1.会根据缓存的启用来包装 CacheExecutor
     * 2.使用 Interceptor 对执行器进行加强
     * @param transaction 事务对象
     * @param executorType 执行器类型
     * @return
     */
    public Executor newExecutor(Transaction transaction, ExecutorType executorType) {
        executorType = executorType == null ? defaultExecutorType : executorType;
        Executor executor;
        // 根据不同类型创建不同执行器
        if (ExecutorType.BATCH == executorType) {
            executor = new BatchExecutor(this, transaction);
        } else if (ExecutorType.REUSE == executorType) {
            executor = new ReuseExecutor(this, transaction);
        } else {
            executor = new SimpleExecutor(this, transaction);
        }
        // 是否开启缓存，如果开启则对执行器包装一层 CachingExecutor ，使其具有缓存能力
        if (cacheEnabled) {
            executor = new CachingExecutor(executor);
        }
        // 将拦截链中的拦截器进行应用，使用 Interceptor 对当前执行器不断进行代理
        return (Executor) interceptorChain.pluginAll(executor);
    }

    public void addKeyGenerator(String id, KeyGenerator keyGenerator) {
        keyGenerators.put(id, keyGenerator);
    }

    public Collection<String> getKeyGeneratorNames() {
        return keyGenerators.keySet();
    }

    public Collection<KeyGenerator> getKeyGenerators() {
        return keyGenerators.values();
    }

    public KeyGenerator getKeyGenerator(String id) {
        return keyGenerators.get(id);
    }

    public boolean hasKeyGenerator(String id) {
        return keyGenerators.containsKey(id);
    }

    /** 使用 namespace - cache 的映射 */
    public void addCache(Cache cache) {
        caches.put(cache.getId(), cache);
    }

    public Collection<String> getCacheNames() {
        return caches.keySet();
    }

    public Collection<Cache> getCaches() {
        return caches.values();
    }

    public Cache getCache(String id) {
        return caches.get(id);
    }

    public boolean hasCache(String id) {
        return caches.containsKey(id);
    }

    public void addResultMap(ResultMap rm) {
        // 注册ResultMap
        resultMaps.put(rm.getId(), rm);
        // 校验当前的 ResultMap 的 discriminator 有没有嵌套 ResultMap
        checkLocallyForDiscriminatedNestedResultMaps(rm);
        // 校验全局的 discriminator 有没有嵌套 ResultMap
        checkGloballyForDiscriminatedNestedResultMaps(rm);
    }

    public Collection<String> getResultMapNames() {
        return resultMaps.keySet();
    }

    public Collection<ResultMap> getResultMaps() {
        return resultMaps.values();
    }

    public ResultMap getResultMap(String id) {
        return resultMaps.get(id);
    }

    public boolean hasResultMap(String id) {
        return resultMaps.containsKey(id);
    }

    public void addParameterMap(ParameterMap pm) {
        parameterMaps.put(pm.getId(), pm);
    }

    public Collection<String> getParameterMapNames() {
        return parameterMaps.keySet();
    }

    public Collection<ParameterMap> getParameterMaps() {
        return parameterMaps.values();
    }

    public ParameterMap getParameterMap(String id) {
        return parameterMaps.get(id);
    }

    public boolean hasParameterMap(String id) {
        return parameterMaps.containsKey(id);
    }

    /**
     * 注册 MappedStatement
     * @param ms
     */
    public void addMappedStatement(MappedStatement ms) {
        mappedStatements.put(ms.getId(), ms);
    }

    public Collection<String> getMappedStatementNames() {
        buildAllStatements();
        return mappedStatements.keySet();
    }

    public Collection<MappedStatement> getMappedStatements() {
        buildAllStatements();
        return mappedStatements.values();
    }

    /**
     * @deprecated call {@link #parsePendingStatements(boolean)}
     */
    @Deprecated
    public Collection<XMLStatementBuilder> getIncompleteStatements() {
        return incompleteStatements;
    }

    public void addIncompleteStatement(XMLStatementBuilder incompleteStatement) {
        incompleteStatementsLock.lock();
        try {
            incompleteStatements.add(incompleteStatement);
        } finally {
            incompleteStatementsLock.unlock();
        }
    }

    /**
     * @deprecated call {@link #parsePendingCacheRefs(boolean)}
     */
    @Deprecated
    public Collection<CacheRefResolver> getIncompleteCacheRefs() {
        return incompleteCacheRefs;
    }

    public void addIncompleteCacheRef(CacheRefResolver incompleteCacheRef) {
        incompleteCacheRefsLock.lock();
        try {
            incompleteCacheRefs.add(incompleteCacheRef);
        } finally {
            incompleteCacheRefsLock.unlock();
        }
    }

    /**
     * @deprecated call {@link #parsePendingResultMaps(boolean)}
     */
    @Deprecated
    public Collection<ResultMapResolver> getIncompleteResultMaps() {
        return incompleteResultMaps;
    }

    public void addIncompleteResultMap(ResultMapResolver resultMapResolver) {
        incompleteResultMapsLock.lock();
        try {
            incompleteResultMaps.add(resultMapResolver);
        } finally {
            incompleteResultMapsLock.unlock();
        }
    }

    public void addIncompleteMethod(MethodResolver builder) {
        incompleteMethodsLock.lock();
        try {
            incompleteMethods.add(builder);
        } finally {
            incompleteMethodsLock.unlock();
        }
    }

    /**
     * @deprecated call {@link #parsePendingMethods(boolean)}
     */
    @Deprecated
    public Collection<MethodResolver> getIncompleteMethods() {
        return incompleteMethods;
    }

    /**
     * 获取 MappedStatement 对象
     * @param id
     * @return
     */
    public MappedStatement getMappedStatement(String id) {
        return this.getMappedStatement(id, true);
    }

    /**
     * 获取 MappedStatement，并根据入参判断是否需要确保所有 statement 都初始化完成
     * @param id
     * @param validateIncompleteStatements 判断是否校验未完成的 MappedStatement
     * @return
     */
    public MappedStatement getMappedStatement(String id, boolean validateIncompleteStatements) {
        if (validateIncompleteStatements) {
            buildAllStatements();
        }
        return mappedStatements.get(id);
    }

    public Map<String, XNode> getSqlFragments() {
        return sqlFragments;
    }

    public void addInterceptor(Interceptor interceptor) {
        interceptorChain.addInterceptor(interceptor);
    }

    public void addMappers(String packageName, Class<?> superType) {
        mapperRegistry.addMappers(packageName, superType);
    }

    public void addMappers(String packageName) {
        mapperRegistry.addMappers(packageName);
    }

    /**
     * 添加mapper接口类，并进行注册
     * 该方法会通过 MapperAnnotationBuilder 找到对应 mapper 类加载解析 MappedStatement
     * @param type
     * @param <T>
     */
    public <T> void addMapper(Class<T> type) {
        mapperRegistry.addMapper(type);
    }

    /**
     * 从 MapperRegistry 中获取 mapper 对象
     * @param type
     * @param sqlSession
     * @return
     * @param <T>
     */
    public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
        return mapperRegistry.getMapper(type, sqlSession);
    }

    public boolean hasMapper(Class<?> type) {
        return mapperRegistry.hasMapper(type);
    }

    public boolean hasStatement(String statementName) {
        return hasStatement(statementName, true);
    }

    public boolean hasStatement(String statementName, boolean validateIncompleteStatements) {
        if (validateIncompleteStatements) {
            buildAllStatements();
        }
        return mappedStatements.containsKey(statementName);
    }

    public void addCacheRef(String namespace, String referencedNamespace) {
        cacheRefMap.put(namespace, referencedNamespace);
    }

    /*
     * Parses all the unprocessed statement nodes in the cache. It is recommended to call this method once all the mappers
     * are added as it provides fail-fast statement validation.
     */
    protected void buildAllStatements() {
        parsePendingResultMaps(true);
        parsePendingCacheRefs(true);
        parsePendingStatements(true);
        parsePendingMethods(true);
    }

    /**
     * 继续解析 org.apache.ibatis.builder.annotation.MapperAnnotationBuilder#parse() 时未完成的 method
     * @param reportUnresolved
     */
    public void parsePendingMethods(boolean reportUnresolved) {
        if (incompleteMethods.isEmpty()) {
            return;
        }
        incompleteMethodsLock.lock();
        try {
            incompleteMethods.removeIf(x -> {
                x.resolve();
                return true;
            });
        } catch (IncompleteElementException e) {
            if (reportUnresolved) {
                throw e;
            }
        } finally {
            incompleteMethodsLock.unlock();
        }
    }

    public void parsePendingStatements(boolean reportUnresolved) {
        if (incompleteStatements.isEmpty()) {
            return;
        }
        incompleteStatementsLock.lock();
        try {
            incompleteStatements.removeIf(x -> {
                x.parseStatementNode();
                return true;
            });
        } catch (IncompleteElementException e) {
            if (reportUnresolved) {
                throw e;
            }
        } finally {
            incompleteStatementsLock.unlock();
        }
    }

    public void parsePendingCacheRefs(boolean reportUnresolved) {
        if (incompleteCacheRefs.isEmpty()) {
            return;
        }
        incompleteCacheRefsLock.lock();
        try {
            incompleteCacheRefs.removeIf(x -> x.resolveCacheRef() != null);
        } catch (IncompleteElementException e) {
            if (reportUnresolved) {
                throw e;
            }
        } finally {
            incompleteCacheRefsLock.unlock();
        }
    }

    /**
     * 解析未处理完成的 ResultMap
     * @param reportUnresolved 如果仍存在 IncompleteElementException 时，是否抛出
     */
    public void parsePendingResultMaps(boolean reportUnresolved) {
        if (incompleteResultMaps.isEmpty()) {
            return;
        }
        // 加锁，保证数据安全
        incompleteResultMapsLock.lock();
        try {
            boolean resolved;
            IncompleteElementException ex = null;
            do {
                resolved = false;
                Iterator<ResultMapResolver> iterator = incompleteResultMaps.iterator();
                while (iterator.hasNext()) {
                    try {
                        iterator.next().resolve(); // 继续尝试解析
                        iterator.remove(); // 解析成功则移除
                        resolved = true;
                    } catch (IncompleteElementException e) {
                        ex = e;
                    }
                }
            } while (resolved);
            // 如果需要抛出移除，并且仍存在未完成的 ResultMap 则抛出异常
            if (reportUnresolved && !incompleteResultMaps.isEmpty() && ex != null) {
                // At least one result map is unresolvable.
                throw ex;
            }
        } finally {
            incompleteResultMapsLock.unlock();
        }
    }

    /**
     * Extracts namespace from fully qualified statement id.
     *
     * @param statementId the statement id
     * @return namespace or null when id does not contain period.
     */
    protected String extractNamespace(String statementId) {
        int lastPeriod = statementId.lastIndexOf('.');
        return lastPeriod > 0 ? statementId.substring(0, lastPeriod) : null;
    }

    // Slow but a one time cost. A better solution is welcome.
    protected void checkGloballyForDiscriminatedNestedResultMaps(ResultMap rm) {
        // 如果当前 ResultMap 变为存在嵌套，那么需要重新对全局所有的 ResultMap 进行 discriminator 嵌套判断处理
        if (rm.hasNestedResultMaps()) {
            final String resultMapId = rm.getId();
            for (Object resultMapObject : resultMaps.values()) {
                if (resultMapObject instanceof ResultMap) {
                    ResultMap entryResultMap = (ResultMap) resultMapObject;
                    if (!entryResultMap.hasNestedResultMaps() && entryResultMap.getDiscriminator() != null) {
                        Collection<String> discriminatedResultMapNames = entryResultMap.getDiscriminator().getDiscriminatorMap()
                                .values();
                        if (discriminatedResultMapNames.contains(resultMapId)) {
                            entryResultMap.forceNestedResultMaps();
                        }
                    }
                }
            }
        }
    }

    // Slow but a one time cost. A better solution is welcome.
    protected void checkLocallyForDiscriminatedNestedResultMaps(ResultMap rm) {
        // 如果存在嵌套 ResultMap 并且配置了 <discriminator> 才进行校验
        if (!rm.hasNestedResultMaps() && rm.getDiscriminator() != null) {
            // 遍历 value-resultMapId 的map
            for (String discriminatedResultMapName : rm.getDiscriminator().getDiscriminatorMap().values()) {
                if (hasResultMap(discriminatedResultMapName)) { // 判断对应的 discriminator 的 resultMap 是否注册
                    // 如果引用的 discriminator 的 resultMap 有嵌套，则修改父 ResultMap 的嵌套状态，让后续操作无需反复判断是否有嵌套
                    ResultMap discriminatedResultMap = resultMaps.get(discriminatedResultMapName);
                    if (discriminatedResultMap.hasNestedResultMaps()) {
                        rm.forceNestedResultMaps();
                        break;
                    }
                }
            }
        }
    }

    protected static class StrictMap<V> extends ConcurrentHashMap<String, V> {

        private static final long serialVersionUID = -4950446264854982944L;
        private final String name;
        private BiFunction<V, V, String> conflictMessageProducer;

        public StrictMap(String name, int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
            this.name = name;
        }

        public StrictMap(String name, int initialCapacity) {
            super(initialCapacity);
            this.name = name;
        }

        public StrictMap(String name) {
            this.name = name;
        }

        public StrictMap(String name, Map<String, ? extends V> m) {
            super(m);
            this.name = name;
        }

        /**
         * Assign a function for producing a conflict error message when contains value with the same key.
         * <p>
         * function arguments are 1st is saved value and 2nd is target value.
         *
         * @param conflictMessageProducer A function for producing a conflict error message
         * @return a conflict error message
         * @since 3.5.0
         */
        public StrictMap<V> conflictMessageProducer(BiFunction<V, V, String> conflictMessageProducer) {
            this.conflictMessageProducer = conflictMessageProducer;
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V put(String key, V value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException(name + " already contains key " + key
                        + (conflictMessageProducer == null ? "" : conflictMessageProducer.apply(super.get(key), value)));
            }
            if (key.contains(".")) {
                final String shortKey = getShortName(key);
                if (super.get(shortKey) == null) {
                    super.put(shortKey, value);
                } else {
                    super.put(shortKey, (V) new Ambiguity(shortKey));
                }
            }
            return super.put(key, value);
        }

        @Override
        public boolean containsKey(Object key) {
            if (key == null) {
                return false;
            }

            return super.get(key) != null;
        }

        @Override
        public V get(Object key) {
            V value = super.get(key);
            if (value == null) {
                throw new IllegalArgumentException(name + " does not contain value for " + key);
            }
            if (value instanceof Ambiguity) {
                throw new IllegalArgumentException(((Ambiguity) value).getSubject() + " is ambiguous in " + name
                        + " (try using the full name including the namespace, or rename one of the entries)");
            }
            return value;
        }

        protected static class Ambiguity {
            private final String subject;

            public Ambiguity(String subject) {
                this.subject = subject;
            }

            public String getSubject() {
                return subject;
            }
        }

        private String getShortName(String key) {
            final String[] keyParts = key.split("\\.");
            return keyParts[keyParts.length - 1];
        }
    }

}
