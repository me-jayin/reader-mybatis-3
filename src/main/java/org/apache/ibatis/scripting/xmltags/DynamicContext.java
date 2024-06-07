/*
 *    Copyright 2009-2023 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * @author Clinton Begin
 */
public class DynamicContext {

    public static final String PARAMETER_OBJECT_KEY = "_parameter";
    public static final String DATABASE_ID_KEY = "_databaseId";

    static {
        // 针对 ContextMap 类型，设置对应的访问器
        OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
    }

    /**
     * 持有当前动态sql入参上下文
     */
    private final ContextMap bindings;
    /**
     * 表示当前sql语句拼接器，在 SqlNode.apply(DynamicContext) 中会将符合条件的sql拼接上去
     */
    private final StringJoiner sqlBuilder = new StringJoiner(" ");
    private int uniqueNumber;

    /**
     * @param configuration
     * @param parameterObject
     */
    public DynamicContext(Configuration configuration, Object parameterObject) {
        // 如果入参非空，并且入参不是 Map 类型，则使用 MetaObject 进行包装
        if (parameterObject != null && !(parameterObject instanceof Map)) {
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            boolean existsTypeHandler = configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
            bindings = new ContextMap(metaObject, existsTypeHandler);
        } else {
            bindings = new ContextMap(null, false);
        }
        // 往参数上下文中添加 _parameter 参数，以及 _databaseId 数据库标识 参数
        bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
        bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
    }

    public Map<String, Object> getBindings() {
        return bindings;
    }

    public void bind(String name, Object value) {
        bindings.put(name, value);
    }

    public void appendSql(String sql) {
        sqlBuilder.add(sql);
    }

    public String getSql() {
        return sqlBuilder.toString().trim();
    }

    public int getUniqueNumber() {
        return uniqueNumber++;
    }

    static class ContextMap extends HashMap<String, Object> {
        private static final long serialVersionUID = 2977601501966151582L;
        private final MetaObject parameterMetaObject;
        private final boolean fallbackParameterObject;

        public ContextMap(MetaObject parameterMetaObject, boolean fallbackParameterObject) {
            this.parameterMetaObject = parameterMetaObject;
            this.fallbackParameterObject = fallbackParameterObject;
        }

        @Override
        public Object get(Object key) {
            // 直接从属性中获取
            String strKey = (String) key;
            if (super.containsKey(strKey)) {
                return super.get(strKey);
            }
            // 如果为null，说明可能入参是map类型，直接交给 ContextAccessor 来处理
            if (parameterMetaObject == null) {
                return null;
            }
            // 配置了TypeHandler并且不存该属性的get方法，则返回参数值
            if (fallbackParameterObject && !parameterMetaObject.hasGetter(strKey)) {
                return parameterMetaObject.getOriginalObject();
            }
            // 直接调用入参对应属性的get方法中获取
            return parameterMetaObject.getValue(strKey);
        }
    }

    /**
     * ongl对 ContextMap 的访问器
     */
    static class ContextAccessor implements PropertyAccessor {
        /**
         * 获取属性值
         * @param context
         * @param target 当前 DynamicContext 对象
         * @param name
         * @return
         */
        @Override
        public Object getProperty(OgnlContext context, Object target, Object name) {
            Map map = (Map) target;

            Object result = map.get(name);
            if (map.containsKey(name) || result != null) {
                return result;
            }

            // 如果入参是 map 时，则从入参中获取值
            Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
            if (parameterObject instanceof Map) {
                return ((Map) parameterObject).get(name);
            }

            return null;
        }

        @Override
        public void setProperty(OgnlContext context, Object target, Object name, Object value) {
            Map<Object, Object> map = (Map<Object, Object>) target;
            map.put(name, value);
        }

        @Override
        public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
            return null;
        }

        @Override
        public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
            return null;
        }
    }
}
