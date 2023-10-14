package com.fasterxml.jackson.databind.deser;

import java.lang.reflect.Type;
import java.util.*;

import com.fasterxml.jackson.annotation.ObjectIdGenerator;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.annotation.ObjectIdResolver;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.cfg.DeserializerFactoryConfig;
import com.fasterxml.jackson.databind.deser.impl.*;
import com.fasterxml.jackson.databind.deser.std.ThrowableDeserializer;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.util.ArrayBuilders;
import com.fasterxml.jackson.databind.util.ClassUtil;
import com.fasterxml.jackson.databind.util.SimpleBeanPropertyDefinition;

/**
 * Concrete deserializer factory class that adds full Bean deserializer
 * construction logic using class introspection.
 * Note that factories specifically do not implement any form of caching:
 * aside from configuration they are stateless; caching is implemented
 * by other components.
 *<p>
 * Instances of this class are fully immutable as all configuration is
 * done by using "fluent factories" (methods that construct new factory
 * instances with different configuration, instead of modifying instance).
 */
public class BeanDeserializerFactory
    extends BasicDeserializerFactory
    implements java.io.Serializable // since 2.1
{
    private static final long serialVersionUID = 1;

    /**
     * Signature of <b>Throwable.initCause</b> method.
     */
    private final static Class<?>[] INIT_CAUSE_PARAMS = new Class<?>[] { Throwable.class };

    private final static Class<?>[] NO_VIEWS = new Class<?>[0];

    /**
     * Set of well-known "nasty classes", deserialization of which is considered dangerous
     * and should (and is) prevented by default.
     */
    private final static Set<String> DEFAULT_NO_DESER_CLASS_NAMES;
    static {
        Set<String> s = new HashSet<String>();
        // Courtesy of [https://github.com/kantega/notsoserial]:
        // (and wrt [databind#1599])
        s.add("org.apache.commons.collections.functors.InvokerTransformer");
        s.add("org.apache.commons.collections.functors.InstantiateTransformer");
        s.add("org.apache.commons.collections4.functors.InvokerTransformer");
        s.add("org.apache.commons.collections4.functors.InstantiateTransformer");
        s.add("org.codehaus.groovy.runtime.ConvertedClosure");
        s.add("org.codehaus.groovy.runtime.MethodClosure");
        s.add("org.springframework.beans.factory.ObjectFactory");
        s.add("com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl");
        s.add("org.apache.xalan.xsltc.trax.TemplatesImpl");
        // [databind#1680]: may or may not be problem, take no chance
        s.add("com.sun.rowset.JdbcRowSetImpl");
        // [databind#1737]; JDK provided
        s.add("java.util.logging.FileHandler");
        s.add("java.rmi.server.UnicastRemoteObject");
        // [databind#1737]; 3rd party
        //s.add("org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor"); // deprecated by [databind#1855]
        s.add("org.springframework.beans.factory.config.PropertyPathFactoryBean");
        // [databind#2680]
        s.add("org.springframework.aop.config.MethodLocatingFactoryBean");
        s.add("org.springframework.beans.factory.config.BeanReferenceFactoryBean");

        // s.add("com.mchange.v2.c3p0.JndiRefForwardingDataSource"); // deprecated by [databind#1931]
        // s.add("com.mchange.v2.c3p0.WrapperConnectionPoolDataSource"); // - "" -
        // [databind#1855]: more 3rd party
        s.add("org.apache.tomcat.dbcp.dbcp2.BasicDataSource");
        s.add("com.sun.org.apache.bcel.internal.util.ClassLoader");
        // [databind#1899]: more 3rd party
        s.add("org.hibernate.jmx.StatisticsService");
        s.add("org.apache.ibatis.datasource.jndi.JndiDataSourceFactory");
        // [databind#2032]: more 3rd party; data exfiltration via xml parsed ext entities
        s.add("org.apache.ibatis.parsing.XPathParser");

        // [databind#2052]: Jodd-db, with jndi/ldap lookup
        s.add("jodd.db.connection.DataSourceConnectionProvider");

        // [databind#2058]: Oracle JDBC driver, with jndi/ldap lookup
        s.add("oracle.jdbc.connector.OracleManagedConnectionFactory");
        s.add("oracle.jdbc.rowset.OracleJDBCRowSet");

        // [databind#2097]: some 3rd party, one JDK-bundled
        s.add("org.slf4j.ext.EventData");
        s.add("flex.messaging.util.concurrent.AsynchBeansWorkManagerExecutor");
        s.add("com.sun.deploy.security.ruleset.DRSHelper");
        s.add("org.apache.axis2.jaxws.spi.handler.HandlerResolverImpl");

        // [databind#2186], [databind#2670]: yet more 3rd party gadgets
        s.add("org.jboss.util.propertyeditor.DocumentEditor");
        s.add("org.apache.openjpa.ee.RegistryManagedRuntime");
        s.add("org.apache.openjpa.ee.JNDIManagedRuntime");
        s.add("org.apache.openjpa.ee.WASRegistryManagedRuntime"); // [#2670] addition
        s.add("org.apache.axis2.transport.jms.JMSOutTransportInfo");

        // [databind#2326]
        s.add("com.mysql.cj.jdbc.admin.MiniAdmin");

        // [databind#2334]: logback-core
        s.add("ch.qos.logback.core.db.DriverManagerConnectionSource");

        // [databind#2341]: jdom/jdom2
        s.add("org.jdom.transform.XSLTransformer");
        s.add("org.jdom2.transform.XSLTransformer");

        // [databind#2387], [databind#2460]: EHCache
        s.add("net.sf.ehcache.transaction.manager.DefaultTransactionManagerLookup");
        s.add("net.sf.ehcache.hibernate.EhcacheJtaTransactionManagerLookup");

        // [databind#2389]: logback/jndi
        s.add("ch.qos.logback.core.db.JNDIConnectionSource");

        // [databind#2410]: HikariCP/metricRegistry config
        s.add("com.zaxxer.hikari.HikariConfig");
        // [databind#2449]: and sub-class thereof
        s.add("com.zaxxer.hikari.HikariDataSource");

        // [databind#2420]: CXF/JAX-RS provider/XSLT
        s.add("org.apache.cxf.jaxrs.provider.XSLTJaxbProvider");

        // [databind#2462]: commons-configuration / -2
        s.add("org.apache.commons.configuration.JNDIConfiguration");
        s.add("org.apache.commons.configuration2.JNDIConfiguration");

        // [databind#2469]: xalan
        s.add("org.apache.xalan.lib.sql.JNDIConnectionPool");
        // [databind#2704]: xalan2
        s.add("com.sun.org.apache.xalan.internal.lib.sql.JNDIConnectionPool");

        // [databind#2478]: comons-dbcp, p6spy
        s.add("org.apache.commons.dbcp.datasources.PerUserPoolDataSource");
        s.add("org.apache.commons.dbcp.datasources.SharedPoolDataSource");
        s.add("com.p6spy.engine.spy.P6DataSource");

        // [databind#2498]: log4j-extras (1.2)
        s.add("org.apache.log4j.receivers.db.DriverManagerConnectionSource");
        s.add("org.apache.log4j.receivers.db.JNDIConnectionSource");

        // [databind#2526]: some more ehcache
        s.add("net.sf.ehcache.transaction.manager.selector.GenericJndiSelector");
        s.add("net.sf.ehcache.transaction.manager.selector.GlassfishSelector");

        // [databind#2620]: xbean-reflect
        s.add("org.apache.xbean.propertyeditor.JndiConverter");

        // [databind#2631]: shaded hikari-config
        s.add("org.apache.hadoop.shaded.com.zaxxer.hikari.HikariConfig");

        // [databind#2634]: ibatis-sqlmap, anteros-core/-dbcp
        s.add("com.ibatis.sqlmap.engine.transaction.jta.JtaTransactionConfig");
        s.add("br.com.anteros.dbcp.AnterosDBCPConfig");
        // [databind#2814]: anteros-dbcp
        s.add("br.com.anteros.dbcp.AnterosDBCPDataSource");

        // [databind#2642][databind#2854]: javax.swing (jdk)
        s.add("javax.swing.JEditorPane");
        s.add("javax.swing.JTextPane");

        // [databind#2648], [databind#2653]: shiro-core
        s.add("org.apache.shiro.realm.jndi.JndiRealmFactory");
        s.add("org.apache.shiro.jndi.JndiObjectFactory");

        // [databind#2658]: ignite-jta (, quartz-core)
        s.add("org.apache.ignite.cache.jta.jndi.CacheJndiTmLookup");
        s.add("org.apache.ignite.cache.jta.jndi.CacheJndiTmFactory");
        s.add("org.quartz.utils.JNDIConnectionProvider");

        // [databind#2659]: aries.transaction.jms
        s.add("org.apache.aries.transaction.jms.internal.XaPooledConnectionFactory");
        s.add("org.apache.aries.transaction.jms.RecoverablePooledConnectionFactory");

        // [databind#2660]: caucho-quercus
        s.add("com.caucho.config.types.ResourceRef");

        // [databind#2662]: aoju/bus-proxy
        s.add("org.aoju.bus.proxy.provider.RmiProvider");
        s.add("org.aoju.bus.proxy.provider.remoting.RmiProvider");

        // [databind#2664]: activemq-core, activemq-pool, activemq-pool-jms

        s.add("org.apache.activemq.ActiveMQConnectionFactory"); // core
        s.add("org.apache.activemq.ActiveMQXAConnectionFactory");
        s.add("org.apache.activemq.spring.ActiveMQConnectionFactory");
        s.add("org.apache.activemq.spring.ActiveMQXAConnectionFactory");
        s.add("org.apache.activemq.pool.JcaPooledConnectionFactory"); // pool
        s.add("org.apache.activemq.pool.PooledConnectionFactory");
        s.add("org.apache.activemq.pool.XaPooledConnectionFactory");
        s.add("org.apache.activemq.jms.pool.XaPooledConnectionFactory"); // pool-jms
        s.add("org.apache.activemq.jms.pool.JcaPooledConnectionFactory");

        // [databind#2666]: apache/commons-jms
        s.add("org.apache.commons.proxy.provider.remoting.RmiProvider");

        // [databind#2682]: commons-jelly
        s.add("org.apache.commons.jelly.impl.Embedded");

        // [databind#2688]: apache/drill
        s.add("oadd.org.apache.xalan.lib.sql.JNDIConnectionPool");

        // [databind#2698]: weblogic w/ oracle/aq-jms
        // (note: dependency not available via Maven Central, but as part of
        // weblogic installation, possibly fairly old version(s))
        s.add("oracle.jms.AQjmsQueueConnectionFactory");
        s.add("oracle.jms.AQjmsXATopicConnectionFactory");
        s.add("oracle.jms.AQjmsTopicConnectionFactory");
        s.add("oracle.jms.AQjmsXAQueueConnectionFactory");
        s.add("oracle.jms.AQjmsXAConnectionFactory");

        // [databind#2765]: org.jsecurity:
        s.add("org.jsecurity.realm.jndi.JndiRealmFactory");

        // [databind#2798]: com.pastdev.httpcomponents:
        s.add("com.pastdev.httpcomponents.configuration.JndiConfiguration");

        // [databind#2826], [databind#2827]
        s.add("com.nqadmin.rowset.JdbcRowSetImpl");
        s.add("org.arrah.framework.rdbms.UpdatableJdbcRowsetImpl");

        DEFAULT_NO_DESER_CLASS_NAMES = Collections.unmodifiableSet(s);
    }

    /**
     * Set of class names of types that are never to be deserialized.
     */
    private Set<String> _cfgIllegalClassNames = DEFAULT_NO_DESER_CLASS_NAMES;

    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */
    
    /**
     * Globally shareable thread-safe instance which has no additional custom deserializers
     * registered
     */
    public final static BeanDeserializerFactory instance = new BeanDeserializerFactory(
            new DeserializerFactoryConfig());

    public BeanDeserializerFactory(DeserializerFactoryConfig config) {
        super(config);
    }
    
    /**
     * Method used by module registration functionality, to construct a new bean
     * deserializer factory
     * with different configuration settings.
     */
    @Override
    public DeserializerFactory withConfig(DeserializerFactoryConfig config)
    {
        if (_factoryConfig == config) {
            return this;
        }
        /* 22-Nov-2010, tatu: Handling of subtypes is tricky if we do immutable-with-copy-ctor;
         *    and we pretty much have to here either choose between losing subtype instance
         *    when registering additional deserializers, or losing deserializers.
         *    Instead, let's actually just throw an error if this method is called when subtype
         *    has not properly overridden this method; this to indicate problem as soon as possible.
         */
        if (getClass() != BeanDeserializerFactory.class) {
            throw new IllegalStateException("Subtype of BeanDeserializerFactory ("+getClass().getName()
                    +") has not properly overridden method 'withAdditionalDeserializers': can not instantiate subtype with "
                    +"additional deserializer definitions");
        }
        return new BeanDeserializerFactory(config);
    }
    
    /*
    /**********************************************************
    /* DeserializerFactory API implementation
    /**********************************************************
     */

    /**
     * Method that {@link DeserializerCache}s call to create a new
     * deserializer for types other than Collections, Maps, arrays and
     * enums.
     */
    @Override
    public JsonDeserializer<Object> createBeanDeserializer(DeserializationContext ctxt,
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        final DeserializationConfig config = ctxt.getConfig();
        // We may also have custom overrides:
        JsonDeserializer<Object> custom = _findCustomBeanDeserializer(type, config, beanDesc);
        if (custom != null) {
            return custom;
        }
        /* One more thing to check: do we have an exception type
         * (Throwable or its sub-classes)? If so, need slightly
         * different handling.
         */
        if (type.isThrowable()) {
            return buildThrowableDeserializer(ctxt, type, beanDesc);
        }
        /* Or, for abstract types, may have alternate means for resolution
         * (defaulting, materialization)
         */
        if (type.isAbstract()) {
            // [JACKSON-41] (v1.6): Let's make it possible to materialize abstract types.
            JavaType concreteType = materializeAbstractType(ctxt, type, beanDesc);
            if (concreteType != null) {
                /* important: introspect actual implementation (abstract class or
                 * interface doesn't have constructors, for one)
                 */
                beanDesc = config.introspect(concreteType);
                return buildBeanDeserializer(ctxt, concreteType, beanDesc);
            }
        }

        // Otherwise, may want to check handlers for standard types, from superclass:
        @SuppressWarnings("unchecked")
        JsonDeserializer<Object> deser = (JsonDeserializer<Object>) findStdDeserializer(ctxt, type, beanDesc);
        if (deser != null) {
            return deser;
        }

        // Otherwise: could the class be a Bean class? If not, bail out
        if (!isPotentialBeanType(type.getRawClass())) {
            return null;
        }
        // For checks like [databind#1599]
        checkIllegalTypes(ctxt, type, beanDesc);
        // Use generic bean introspection to build deserializer
        return buildBeanDeserializer(ctxt, type, beanDesc);
    }

    @Override
    public JsonDeserializer<Object> createBuilderBasedDeserializer(
    		DeserializationContext ctxt, JavaType valueType, BeanDescription beanDesc,
    		Class<?> builderClass)
        throws JsonMappingException
    {
    	// First: need a BeanDescription for builder class
    	JavaType builderType = ctxt.constructType(builderClass);
    	BeanDescription builderDesc = ctxt.getConfig().introspectForBuilder(builderType);
    	return buildBuilderBasedDeserializer(ctxt, valueType, builderDesc);
    }
    
    /**
     * Method called by {@link BeanDeserializerFactory} to see if there might be a standard
     * deserializer registered for given type.
     */
    protected JsonDeserializer<?> findStdDeserializer(DeserializationContext ctxt,
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        // note: we do NOT check for custom deserializers here, caller has already
        // done that
        JsonDeserializer<?> deser = findDefaultDeserializer(ctxt, type, beanDesc);
        // Also: better ensure these are post-processable?
        if (deser != null) {
            if (_factoryConfig.hasDeserializerModifiers()) {
                for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                    deser = mod.modifyDeserializer(ctxt.getConfig(), beanDesc, deser);
                }
            }
        }
        return deser;
    }
    
    protected JavaType materializeAbstractType(DeserializationContext ctxt,
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        final JavaType abstractType = beanDesc.getType();
        // [JACKSON-502]: Now it is possible to have multiple resolvers too,
        //   as they are registered via module interface.
        for (AbstractTypeResolver r : _factoryConfig.abstractTypeResolvers()) {
            JavaType concrete = r.resolveAbstractType(ctxt.getConfig(), abstractType);
            if (concrete != null) {
                return concrete;
            }
        }
        return null;
    }
    
    /*
    /**********************************************************
    /* Public construction method beyond DeserializerFactory API:
    /* can be called from outside as well as overridden by
    /* sub-classes
    /**********************************************************
     */

    /**
     * Method that is to actually build a bean deserializer instance.
     * All basic sanity checks have been done to know that what we have
     * may be a valid bean type, and that there are no default simple
     * deserializers.
     */
    @SuppressWarnings("unchecked")
    public JsonDeserializer<Object> buildBeanDeserializer(DeserializationContext ctxt,
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        // First: check what creators we can use, if any
        ValueInstantiator valueInstantiator;
        /* 04-Jun-2015, tatu: To work around [databind#636], need to catch the
         *    issue, defer; this seems like a reasonable good place for now.
         *   Note, however, that for non-Bean types (Collections, Maps) this
         *   probably won't work and needs to be added elsewhere.
         */
        try {
            valueInstantiator = findValueInstantiator(ctxt, beanDesc);
        } catch (NoClassDefFoundError error) {
            return new NoClassDefFoundDeserializer<Object>(error);
        }
        BeanDeserializerBuilder builder = constructBeanDeserializerBuilder(ctxt, beanDesc);
        builder.setValueInstantiator(valueInstantiator);
         // And then setters for deserializing from JSON Object
        addBeanProps(ctxt, beanDesc, builder);
        addObjectIdReader(ctxt, beanDesc, builder);

        // managed/back reference fields/setters need special handling... first part
        addReferenceProperties(ctxt, beanDesc, builder);
        addInjectables(ctxt, beanDesc, builder);
        
        final DeserializationConfig config = ctxt.getConfig();
        // [JACKSON-440]: update builder now that all information is in?
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                builder = mod.updateBuilder(config, beanDesc, builder);
            }
        }
        JsonDeserializer<?> deserializer;

        /* 19-Mar-2012, tatu: This check used to be done earlier; but we have to defer
         *   it a bit to collect information on ObjectIdReader, for example.
         */
        if (type.isAbstract() && !valueInstantiator.canInstantiate()) {
            deserializer = builder.buildAbstract();
        } else {
            deserializer = builder.build();
        }

        // [JACKSON-440]: may have modifier(s) that wants to modify or replace serializer we just built:
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                deserializer = mod.modifyDeserializer(config, beanDesc, deserializer);
            }
        }
        return (JsonDeserializer<Object>) deserializer;
    }
    
    /**
     * Method for constructing a bean deserializer that uses specified
     * intermediate Builder for binding data, and construction of the
     * value instance.
     * Note that implementation is mostly copied from the regular
     * BeanDeserializer build method.
     */
    @SuppressWarnings("unchecked")
    protected JsonDeserializer<Object> buildBuilderBasedDeserializer(
    		DeserializationContext ctxt, JavaType valueType, BeanDescription builderDesc)
        throws JsonMappingException
    {
    	// Creators, anyone? (to create builder itself)
        ValueInstantiator valueInstantiator = findValueInstantiator(ctxt, builderDesc);
        final DeserializationConfig config = ctxt.getConfig();
        BeanDeserializerBuilder builder = constructBeanDeserializerBuilder(ctxt, builderDesc);
        builder.setValueInstantiator(valueInstantiator);
         // And then "with methods" for deserializing from JSON Object
        addBeanProps(ctxt, builderDesc, builder);
        addObjectIdReader(ctxt, builderDesc, builder);
        
        // managed/back reference fields/setters need special handling... first part
        addReferenceProperties(ctxt, builderDesc, builder);
        addInjectables(ctxt, builderDesc, builder);

        JsonPOJOBuilder.Value builderConfig = builderDesc.findPOJOBuilderConfig();
        final String buildMethodName = (builderConfig == null) ?
                "build" : builderConfig.buildMethodName;
        
        // and lastly, find build method to use:
        AnnotatedMethod buildMethod = builderDesc.findMethod(buildMethodName, null);
        if (buildMethod != null) { // note: can't yet throw error; may be given build method
            if (config.canOverrideAccessModifiers()) {
            	ClassUtil.checkAndFixAccess(buildMethod.getMember());
            }
        }
        builder.setPOJOBuilder(buildMethod, builderConfig);
        // this may give us more information...
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                builder = mod.updateBuilder(config, builderDesc, builder);
            }
        }
        JsonDeserializer<?> deserializer = builder.buildBuilderBased(
        		valueType, buildMethodName);

        // [JACKSON-440]: may have modifier(s) that wants to modify or replace serializer we just built:
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                deserializer = mod.modifyDeserializer(config, builderDesc, deserializer);
            }
        }
        return (JsonDeserializer<Object>) deserializer;
    }
    
    protected void addObjectIdReader(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanDeserializerBuilder builder)
        throws JsonMappingException
    {
        ObjectIdInfo objectIdInfo = beanDesc.getObjectIdInfo();
        if (objectIdInfo == null) {
            return;
        }
        Class<?> implClass = objectIdInfo.getGeneratorType();
        JavaType idType;
        SettableBeanProperty idProp;
        ObjectIdGenerator<?> gen;

        ObjectIdResolver resolver = ctxt.objectIdResolverInstance(beanDesc.getClassInfo(), objectIdInfo);

        // Just one special case: Property-based generator is trickier
        if (implClass == ObjectIdGenerators.PropertyGenerator.class) { // most special one, needs extra work
            PropertyName propName = objectIdInfo.getPropertyName();
            idProp = builder.findProperty(propName);
            if (idProp == null) {
                throw new IllegalArgumentException("Invalid Object Id definition for "
                        +beanDesc.getBeanClass().getName()+": can not find property with name '"+propName+"'");
            }
            idType = idProp.getType();
            gen = new PropertyBasedObjectIdGenerator(objectIdInfo.getScope());
        } else {
            JavaType type = ctxt.constructType(implClass);
            idType = ctxt.getTypeFactory().findTypeParameters(type, ObjectIdGenerator.class)[0];
            idProp = null;
            gen = ctxt.objectIdGeneratorInstance(beanDesc.getClassInfo(), objectIdInfo);
        }
        // also: unlike with value deserializers, let's just resolve one we need here
        JsonDeserializer<?> deser = ctxt.findRootValueDeserializer(idType);
        builder.setObjectIdReader(ObjectIdReader.construct(idType,
                objectIdInfo.getPropertyName(), gen, deser, idProp, resolver));
    }
    
    @SuppressWarnings("unchecked")
    public JsonDeserializer<Object> buildThrowableDeserializer(DeserializationContext ctxt,
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        final DeserializationConfig config = ctxt.getConfig();
        // first: construct like a regular bean deserializer...
        BeanDeserializerBuilder builder = constructBeanDeserializerBuilder(ctxt, beanDesc);
        builder.setValueInstantiator(findValueInstantiator(ctxt, beanDesc));

        addBeanProps(ctxt, beanDesc, builder);
        // (and assume there won't be any back references)

        // But then let's decorate things a bit
        /* To resolve [JACKSON-95], need to add "initCause" as setter
         * for exceptions (sub-classes of Throwable).
         */
        AnnotatedMethod am = beanDesc.findMethod("initCause", INIT_CAUSE_PARAMS);
        if (am != null) { // should never be null
            SimpleBeanPropertyDefinition propDef = SimpleBeanPropertyDefinition.construct(ctxt.getConfig(), am,
                    new PropertyName("cause"));
            SettableBeanProperty prop = constructSettableProperty(ctxt, beanDesc, propDef,
                    am.getGenericParameterType(0));
            if (prop != null) {
                /* 21-Aug-2011, tatus: We may actually have found 'cause' property
                 *   to set (with new 1.9 code)... but let's replace it just in case,
                 *   otherwise can end up with odd errors.
                 */
                builder.addOrReplaceProperty(prop, true);
            }
        }

        // And also need to ignore "localizedMessage"
        builder.addIgnorable("localizedMessage");
        // [JACKSON-794]: JDK 7 also added "getSuppressed", skip if we have such data:
        builder.addIgnorable("suppressed");
        /* As well as "message": it will be passed via constructor,
         * as there's no 'setMessage()' method
        */
        builder.addIgnorable("message");

        // [JACKSON-440]: update builder now that all information is in?
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                builder = mod.updateBuilder(config, beanDesc, builder);
            }
        }
        JsonDeserializer<?> deserializer = builder.build();
        
        /* At this point it ought to be a BeanDeserializer; if not, must assume
         * it's some other thing that can handle deserialization ok...
         */
        if (deserializer instanceof BeanDeserializer) {
            deserializer = new ThrowableDeserializer((BeanDeserializer) deserializer);
        }

        // [JACKSON-440]: may have modifier(s) that wants to modify or replace serializer we just built:
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                deserializer = mod.modifyDeserializer(config, beanDesc, deserializer);
            }
        }
        return (JsonDeserializer<Object>) deserializer;
    }

    /*
    /**********************************************************
    /* Helper methods for Bean deserializer construction,
    /* overridable by sub-classes
    /**********************************************************
     */

    /**
     * Overridable method that constructs a {@link BeanDeserializerBuilder}
     * which is used to accumulate information needed to create deserializer
     * instance.
     */
    protected BeanDeserializerBuilder constructBeanDeserializerBuilder(DeserializationContext ctxt,
            BeanDescription beanDesc) {
        return new BeanDeserializerBuilder(beanDesc, ctxt.getConfig());
    }
    
    /**
     * Method called to figure out settable properties for the
     * bean deserializer to use.
     *<p>
     * Note: designed to be overridable, and effort is made to keep interface
     * similar between versions.
     */
    protected void addBeanProps(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanDeserializerBuilder builder)
        throws JsonMappingException
    {
        final SettableBeanProperty[] creatorProps =
                builder.getValueInstantiator().getFromObjectArguments(ctxt.getConfig());
        final boolean isConcrete = !beanDesc.getType().isAbstract();
        
        // Things specified as "ok to ignore"? [JACKSON-77]
        AnnotationIntrospector intr = ctxt.getAnnotationIntrospector();
        boolean ignoreAny = false;
        {
            Boolean B = intr.findIgnoreUnknownProperties(beanDesc.getClassInfo());
            if (B != null) {
                ignoreAny = B.booleanValue();
                builder.setIgnoreUnknownProperties(ignoreAny);
            }
        }
        // Or explicit/implicit definitions?
        Set<String> ignored = ArrayBuilders.arrayToSet(intr.findPropertiesToIgnore(beanDesc.getClassInfo(), false));        
        for (String propName : ignored) {
            builder.addIgnorable(propName);
        }
        // Also, do we have a fallback "any" setter?
        AnnotatedMethod anySetter = beanDesc.findAnySetter();
        if (anySetter != null) {
            builder.setAnySetter(constructAnySetter(ctxt, beanDesc, anySetter));
        }
        // NOTE: we do NOT add @JsonIgnore'd properties into blocked ones if there's any-setter
        // Implicit ones via @JsonIgnore and equivalent?
        if (anySetter == null) {
            Collection<String> ignored2 = beanDesc.getIgnoredPropertyNames();
            if (ignored2 != null) {
                for (String propName : ignored2) {
                    // allow ignoral of similarly named JSON property, but do not force;
                    // latter means NOT adding this to 'ignored':
                    builder.addIgnorable(propName);
                }
            }
        }
        final boolean useGettersAsSetters = (ctxt.isEnabled(MapperFeature.USE_GETTERS_AS_SETTERS)
                && ctxt.isEnabled(MapperFeature.AUTO_DETECT_GETTERS));

        // Ok: let's then filter out property definitions
        List<BeanPropertyDefinition> propDefs = filterBeanProps(ctxt,
                beanDesc, builder, beanDesc.findProperties(), ignored);

        // After which we can let custom code change the set
        if (_factoryConfig.hasDeserializerModifiers()) {
            for (BeanDeserializerModifier mod : _factoryConfig.deserializerModifiers()) {
                propDefs = mod.updateProperties(ctxt.getConfig(), beanDesc, propDefs);
            }
        }
        
        // At which point we still have all kinds of properties; not all with mutators:
        for (BeanPropertyDefinition propDef : propDefs) {
            SettableBeanProperty prop = null;
            /* 18-Oct-2013, tatu: Although constructor parameters have highest precedence,
             *   we need to do linkage (as per [Issue#318]), and so need to start with
             *   other types, and only then create constructor parameter, if any.
             */
            if (propDef.hasSetter()) {
                Type propertyType = propDef.getSetter().getGenericParameterType(0);
                prop = constructSettableProperty(ctxt, beanDesc, propDef, propertyType);
            } else if (propDef.hasField()) {
                Type propertyType = propDef.getField().getGenericType();
                prop = constructSettableProperty(ctxt, beanDesc, propDef, propertyType);
            } else if (useGettersAsSetters && propDef.hasGetter()) {
                /* As per [JACKSON-88], may also need to consider getters
                 * for Map/Collection properties; but with lowest precedence
                 */
                AnnotatedMethod getter = propDef.getGetter();
                // should only consider Collections and Maps, for now?
                Class<?> rawPropertyType = getter.getRawType();
                if (Collection.class.isAssignableFrom(rawPropertyType)
                        || Map.class.isAssignableFrom(rawPropertyType)) {
                    prop = constructSetterlessProperty(ctxt, beanDesc, propDef);
                }
            }
            // 25-Sep-2014, tatu: No point in finding constructor parameters for abstract types
            //   (since they are never used anyway)
            if (isConcrete && propDef.hasConstructorParameter()) {
                /* [JACKSON-700] If property is passed via constructor parameter, we must
                 *   handle things in special way. Not sure what is the most optimal way...
                 *   for now, let's just call a (new) method in builder, which does nothing.
                 */
                // but let's call a method just to allow custom builders to be aware...
                final String name = propDef.getName();
                CreatorProperty cprop = null;
                if (creatorProps != null) {
                    for (SettableBeanProperty cp : creatorProps) {
                        if (name.equals(cp.getName()) && (cp instanceof CreatorProperty)) {
                            cprop = (CreatorProperty) cp;
                            break;
                        }
                    }
                }
                if (cprop == null) {
                    throw ctxt.mappingException("Could not find creator property with name '%s' (in class %s)",
                            name, beanDesc.getBeanClass().getName());
                }
                if (prop != null) {
                    cprop.setFallbackSetter(prop);
                }
                prop = cprop;
                builder.addCreatorProperty(cprop);
                continue;
            }

            if (prop != null) {
                Class<?>[] views = propDef.findViews();
                if (views == null) {
                    // one more twist: if default inclusion disabled, need to force empty set of views
                    if (!ctxt.isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION)) {
                        views = NO_VIEWS;
                    }
                }
                // one more thing before adding to builder: copy any metadata
                prop.setViews(views);
                builder.addProperty(prop);
            }
        }
    }
    
    /**
     * Helper method called to filter out explicit ignored properties,
     * as well as properties that have "ignorable types".
     * Note that this will not remove properties that have no
     * setters.
     */
    protected List<BeanPropertyDefinition> filterBeanProps(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanDeserializerBuilder builder,
            List<BeanPropertyDefinition> propDefsIn,
            Set<String> ignored)
        throws JsonMappingException
    {
        ArrayList<BeanPropertyDefinition> result = new ArrayList<BeanPropertyDefinition>(
                Math.max(4, propDefsIn.size()));
        HashMap<Class<?>,Boolean> ignoredTypes = new HashMap<Class<?>,Boolean>();
        // These are all valid setters, but we do need to introspect bit more
        for (BeanPropertyDefinition property : propDefsIn) {
            String name = property.getName();
            if (ignored.contains(name)) { // explicit ignoral using @JsonIgnoreProperties needs to block entries
                continue;
            }
            if (!property.hasConstructorParameter()) { // never skip constructor params
                Class<?> rawPropertyType = null;
                if (property.hasSetter()) {
                    rawPropertyType = property.getSetter().getRawParameterType(0);
                } else if (property.hasField()) {
                    rawPropertyType = property.getField().getRawType();
                }

                // [JACKSON-429] Some types are declared as ignorable as well
                if ((rawPropertyType != null)
                        && (isIgnorableType(ctxt.getConfig(), beanDesc, rawPropertyType, ignoredTypes))) {
                    // important: make ignorable, to avoid errors if value is actually seen
                    builder.addIgnorable(name);
                    continue;
                }
            }
            result.add(property);
        }
        return result;
    }
    
    /**
     * Method that will find if bean has any managed- or back-reference properties,
     * and if so add them to bean, to be linked during resolution phase.
     */
    protected void addReferenceProperties(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanDeserializerBuilder builder)
        throws JsonMappingException
    {
        // and then back references, not necessarily found as regular properties
        Map<String,AnnotatedMember> refs = beanDesc.findBackReferenceProperties();
        if (refs != null) {
            for (Map.Entry<String, AnnotatedMember> en : refs.entrySet()) {
                String name = en.getKey();
                AnnotatedMember m = en.getValue();
                Type genericType;
                if (m instanceof AnnotatedMethod) {
                    genericType = ((AnnotatedMethod) m).getGenericParameterType(0);
                } else {
                    genericType = m.getRawType();
                }
                SimpleBeanPropertyDefinition propDef = SimpleBeanPropertyDefinition.construct(
                		ctxt.getConfig(), m);
                builder.addBackReferenceProperty(name, constructSettableProperty(
                        ctxt, beanDesc, propDef, genericType));
            }
        }
    }
    
    /**
     * Method called locate all members used for value injection (if any),
     * constructor {@link com.fasterxml.jackson.databind.deser.impl.ValueInjector} instances, and add them to builder.
     */
    protected void addInjectables(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanDeserializerBuilder builder)
        throws JsonMappingException
    {
        Map<Object, AnnotatedMember> raw = beanDesc.findInjectables();
        if (raw != null) {
            boolean fixAccess = ctxt.canOverrideAccessModifiers();
            for (Map.Entry<Object, AnnotatedMember> entry : raw.entrySet()) {
                AnnotatedMember m = entry.getValue();
                if (fixAccess) {
                    m.fixAccess(); // to ensure we can call it
                }
                builder.addInjectable(PropertyName.construct(m.getName()),
                        beanDesc.resolveType(m.getGenericType()),
                        beanDesc.getClassAnnotations(), m, entry.getKey());
            }
        }
    }

    /**
     * Method called to construct fallback {@link SettableAnyProperty}
     * for handling unknown bean properties, given a method that
     * has been designated as such setter.
     */
    protected SettableAnyProperty constructAnySetter(DeserializationContext ctxt,
            BeanDescription beanDesc, AnnotatedMethod setter)
        throws JsonMappingException
    {
        if (ctxt.canOverrideAccessModifiers()) {
            setter.fixAccess(); // to ensure we can call it
        }
        // we know it's a 2-arg method, second arg is the value
        JavaType type = beanDesc.bindingsForBeanType().resolveType(setter.getGenericParameterType(1));
        BeanProperty.Std property = new BeanProperty.Std(PropertyName.construct(setter.getName()),
                type, null, beanDesc.getClassAnnotations(), setter,
                PropertyMetadata.STD_OPTIONAL);
        type = resolveType(ctxt, beanDesc, type, setter);

        /* AnySetter can be annotated with @JsonClass (etc) just like a
         * regular setter... so let's see if those are used.
         * Returns null if no annotations, in which case binding will
         * be done at a later point.
         */
        JsonDeserializer<Object> deser = findDeserializerFromAnnotation(ctxt, setter);
        /* Otherwise, method may specify more specific (sub-)class for
         * value (no need to check if explicit deser was specified):
         */
        type = modifyTypeByAnnotation(ctxt, setter, type);
        if (deser == null) {
            deser = type.getValueHandler();
        }
        TypeDeserializer typeDeser = type.getTypeHandler();
        return new SettableAnyProperty(property, setter, type,
                deser, typeDeser);
    }

    /**
     * Method that will construct a regular bean property setter using
     * the given setter method.
     *
     * @return Property constructed, if any; or null to indicate that
     *   there should be no property based on given definitions.
     */
    protected SettableBeanProperty constructSettableProperty(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanPropertyDefinition propDef,
            Type jdkType)
        throws JsonMappingException
    {
        // need to ensure method is callable (for non-public)
        AnnotatedMember mutator = propDef.getNonConstructorMutator();
        if (ctxt.canOverrideAccessModifiers()) {
            mutator.fixAccess();
        }
        // note: this works since we know there's exactly one argument for methods
        JavaType t0 = beanDesc.resolveType(jdkType);

        BeanProperty.Std property = new BeanProperty.Std(propDef.getFullName(),
                t0, propDef.getWrapperName(),
                beanDesc.getClassAnnotations(), mutator, propDef.getMetadata());
        JavaType type = resolveType(ctxt, beanDesc, t0, mutator);
        // did type change?
        if (type != t0) {
            property = property.withType(type);
        }
        
        /* First: does the Method specify the deserializer to use?
         * If so, let's use it.
         */
        JsonDeserializer<Object> propDeser = findDeserializerFromAnnotation(ctxt, mutator);
        type = modifyTypeByAnnotation(ctxt, mutator, type);
        TypeDeserializer typeDeser = type.getTypeHandler();
        SettableBeanProperty prop;
        if (mutator instanceof AnnotatedMethod) {
            prop = new MethodProperty(propDef, type, typeDeser,
                beanDesc.getClassAnnotations(), (AnnotatedMethod) mutator);
        } else {
            prop = new FieldProperty(propDef, type, typeDeser,
                    beanDesc.getClassAnnotations(), (AnnotatedField) mutator);
        }
        if (propDeser != null) {
            prop = prop.withValueDeserializer(propDeser);
        }
        // [JACKSON-235]: need to retain name of managed forward references:
        AnnotationIntrospector.ReferenceProperty ref = propDef.findReferenceType();
        if (ref != null && ref.isManagedReference()) {
            prop.setManagedReferenceName(ref.getName());
        }
        ObjectIdInfo objectIdInfo = propDef.findObjectIdInfo();
        if(objectIdInfo != null){
            prop.setObjectIdInfo(objectIdInfo);
        }
        return prop;
    }

    /**
     * Method that will construct a regular bean property setter using
     * the given setter method.
     */
    protected SettableBeanProperty constructSetterlessProperty(DeserializationContext ctxt,
            BeanDescription beanDesc, BeanPropertyDefinition propDef)
        throws JsonMappingException
    {
        final AnnotatedMethod getter = propDef.getGetter();
        // need to ensure it is callable now:
        if (ctxt.canOverrideAccessModifiers()) {
            getter.fixAccess();
        }

        /* 26-Jan-2012, tatu: Alas, this complication is still needed to handle
         *   (or at least work around) local type declarations...
         */
        JavaType type = getter.getType(beanDesc.bindingsForBeanType());
        /* First: does the Method specify the deserializer to use?
         * If so, let's use it.
         */
        JsonDeserializer<Object> propDeser = findDeserializerFromAnnotation(ctxt, getter);
        type = modifyTypeByAnnotation(ctxt, getter, type);
        // As per [Issue#501], need full resolution:
        type = resolveType(ctxt, beanDesc, type, getter);
        TypeDeserializer typeDeser = type.getTypeHandler();
        SettableBeanProperty prop = new SetterlessProperty(propDef, type, typeDeser,
                beanDesc.getClassAnnotations(), getter);
        if (propDeser != null) {
            prop = prop.withValueDeserializer(propDeser);
        }
        return prop;
    }

    /*
    /**********************************************************
    /* Helper methods for Bean deserializer, other
    /**********************************************************
     */

    /**
     * Helper method used to skip processing for types that we know
     * can not be (i.e. are never consider to be) beans: 
     * things like primitives, Arrays, Enums, and proxy types.
     *<p>
     * Note that usually we shouldn't really be getting these sort of
     * types anyway; but better safe than sorry.
     */
    protected boolean isPotentialBeanType(Class<?> type)
    {
        String typeStr = ClassUtil.canBeABeanType(type);
        if (typeStr != null) {
            throw new IllegalArgumentException("Can not deserialize Class "+type.getName()+" (of type "+typeStr+") as a Bean");
        }
        if (ClassUtil.isProxyType(type)) {
            throw new IllegalArgumentException("Can not deserialize Proxy class "+type.getName()+" as a Bean");
        }
        /* also: can't deserialize some local classes: static are ok; in-method not;
         * and with [JACKSON-594], other non-static inner classes are ok
         */
        typeStr = ClassUtil.isLocalType(type, true);
        if (typeStr != null) {
            throw new IllegalArgumentException("Can not deserialize Class "+type.getName()+" (of type "+typeStr+") as a Bean");
        }
        return true;
    }

    /**
     * Helper method that will check whether given raw type is marked as always ignorable
     * (for purpose of ignoring properties with type)
     */
    protected boolean isIgnorableType(DeserializationConfig config, BeanDescription beanDesc,
            Class<?> type, Map<Class<?>,Boolean> ignoredTypes)
    {
        Boolean status = ignoredTypes.get(type);
        if (status != null) {
            return status.booleanValue();
        }
        BeanDescription desc = config.introspectClassAnnotations(type);
        status = config.getAnnotationIntrospector().isIgnorableType(desc.getClassInfo());
        // We default to 'false', i.e. not ignorable
        return (status == null) ? false : status.booleanValue(); 
    }

    private void checkIllegalTypes(DeserializationContext ctxt, JavaType type,
            BeanDescription beanDesc)
        throws JsonMappingException
    {
        // There are certain nasty classes that could cause problems, mostly
        // via default typing -- catch them here.
        String full = type.getRawClass().getName();

        if (_cfgIllegalClassNames.contains(full)) {
            String message = String.format("Illegal type (%s) to deserialize: prevented for security reasons",
                    full);
            throw ctxt.mappingException("Invalid type definition for type %s: %s",
                    beanDesc, message);
        }
    }
}
