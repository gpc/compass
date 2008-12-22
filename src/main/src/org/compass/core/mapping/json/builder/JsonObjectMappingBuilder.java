/*
 * Copyright 2004-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.compass.core.mapping.json.builder;

import org.compass.core.engine.naming.StaticPropertyPath;
import org.compass.core.mapping.json.PlainJsonObjectMapping;

/**
 * @author kimchy
 */
public class JsonObjectMappingBuilder {

    final PlainJsonObjectMapping mapping;

    public JsonObjectMappingBuilder(String name) {
        this.mapping = new PlainJsonObjectMapping();
        mapping.setName(name);
        if (name != null) {
            mapping.setPath(new StaticPropertyPath(name));
        }
    }

    public JsonObjectMappingBuilder dynamic(boolean dynamic) {
        mapping.setDynamic(dynamic);
        return this;
    }

    public JsonObjectMappingBuilder add(JsonPropertyMappingBuilder builder) {
        mapping.addMapping(builder.mapping);
        return this;
    }

    public JsonObjectMappingBuilder add(JsonObjectMappingBuilder builder) {
        mapping.addMapping(builder.mapping);
        return this;
    }

    public JsonObjectMappingBuilder add(JsonArrayMappingBuilder builder) {
        mapping.addMapping(builder.mapping);
        return this;
    }
}