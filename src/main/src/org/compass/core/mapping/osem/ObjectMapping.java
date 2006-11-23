/*
 * Copyright 2004-2006 the original author or authors.
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

package org.compass.core.mapping.osem;

import org.compass.core.accessor.Getter;
import org.compass.core.accessor.Setter;

/**
 * 
 * @author kimchy
 *
 */
public interface ObjectMapping extends OsemMapping {

    /**
     * Returns the class of the class mapping this object belongs to.
     */
    Class getObjClass();

    /**
     * Sets the class of the class mapping this object belongs to.
     */
    void setObjClass(Class objClass);

    /**
     * Returns the accessor type for this mapping. The accessor type can be
     * field, property or a custom implementation of {@link org.compass.core.accessor.PropertyAccessor}
     * (this can be either the FQN of the class name or a regsitered type in the configuration, see
     * {@link org.compass.core.accessor.PropertyAccessorFactory}.
     */
    String getAccessor();

    /**
     * Sets the accessor type for this mapping. The accessor type can be
     * field, property or a custom implementation of {@link org.compass.core.accessor.PropertyAccessor}
     * (this can be either the FQN of the class name or a regsitered type in the configuration, see
     * {@link org.compass.core.accessor.PropertyAccessorFactory}.
     */
    void setAccessor(String accessor);

    /**
     * Returns the class property name of the object mapping.
     */
    String getPropertyName();

    /**
     * Sets the class property name of the object mapping.
     */
    void setPropertyName(String propertyName);

    /**
     * Returns which alias (or if not present, the FQN of the class name)
     * this object property is defined at.
     */
    String getDefinedInAlias();
    
    /**
     * Sets which alias (or if not present, the FQN of the class name)
     * this object property is defined at.
     */
    void setDefinedInAlias(String alias);

    Getter getGetter();

    void setGetter(Getter getter);

    Setter getSetter();

    void setSetter(Setter setter);

    /**
     * Returns <code>true</code> if this object mapping can be wrapped
     * with a Collection or an Array.
     */
    boolean canBeCollectionWrapped();
}
