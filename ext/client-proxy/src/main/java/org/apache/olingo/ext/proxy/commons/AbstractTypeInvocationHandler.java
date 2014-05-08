/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.ext.proxy.commons;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.olingo.client.api.CommonEdmEnabledODataClient;
import org.apache.olingo.client.api.communication.response.ODataRetrieveResponse;
import org.apache.olingo.client.core.uri.URIUtils;
import org.apache.olingo.commons.api.domain.CommonODataEntity;
import org.apache.olingo.commons.api.domain.CommonODataProperty;
import org.apache.olingo.commons.api.domain.ODataComplexValue;
import org.apache.olingo.commons.api.domain.ODataInlineEntity;
import org.apache.olingo.commons.api.domain.ODataInlineEntitySet;
import org.apache.olingo.commons.api.domain.ODataLink;
import org.apache.olingo.commons.api.domain.ODataLinked;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.ext.proxy.EntityContainerFactory;
import org.apache.olingo.ext.proxy.api.AbstractEntityCollection;
import org.apache.olingo.ext.proxy.api.annotations.ComplexType;
import org.apache.olingo.ext.proxy.api.annotations.EntityType;
import org.apache.olingo.ext.proxy.api.annotations.NavigationProperty;
import org.apache.olingo.ext.proxy.api.annotations.Property;
import org.apache.olingo.ext.proxy.context.AttachedEntityStatus;
import org.apache.olingo.ext.proxy.context.EntityContext;
import org.apache.olingo.ext.proxy.utils.ClassUtils;

public abstract class AbstractTypeInvocationHandler<C extends CommonEdmEnabledODataClient<?>>
        extends AbstractInvocationHandler<C> {

  private static final long serialVersionUID = 2629912294765040037L;

  protected final Class<?> typeRef;

  protected Map<String, Object> propertyChanges = new HashMap<String, Object>();

  protected Map<NavigationProperty, Object> linkChanges = new HashMap<NavigationProperty, Object>();

  protected int propertiesTag;

  protected int linksTag;

  protected final EntityContext entityContext = EntityContainerFactory.getContext().entityContext();

  protected final EntityTypeInvocationHandler<C> targetHandler;

  protected Object internal;

  @SuppressWarnings("unchecked")
  protected AbstractTypeInvocationHandler(
          final C client,
          final Class<?> typeRef,
          final Object internal,
          final EntityContainerInvocationHandler<C> containerHandler) {
    super(client, containerHandler);
    this.internal = internal;
    this.typeRef = typeRef;
    this.propertiesTag = 0;
    this.linksTag = 0;
    this.targetHandler = EntityTypeInvocationHandler.class.cast(this);
  }

  protected AbstractTypeInvocationHandler(
          final C client,
          final Class<?> typeRef,
          final Object internal,
          final EntityTypeInvocationHandler<C> targetHandler) {
    super(client, targetHandler.containerHandler);
    this.internal = internal;
    this.typeRef = typeRef;
    this.propertiesTag = 0;
    this.linksTag = 0;
    this.targetHandler = targetHandler;
  }

  public abstract FullQualifiedName getName();

  public Class<?> getTypeRef() {
    return typeRef;
  }

  public Map<String, Object> getPropertyChanges() {
    return propertyChanges;
  }

  public Map<NavigationProperty, Object> getLinkChanges() {
    return linkChanges;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
    if (isSelfMethod(method, args)) {
      return invokeSelfMethod(method, args);
    } else if ("operations".equals(method.getName()) && ArrayUtils.isEmpty(args)) {
      final Class<?> returnType = method.getReturnType();

      return Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {returnType},
              OperationInvocationHandler.getInstance(targetHandler));
    } else if (method.getName().startsWith("get")) {
      // Assumption: for each getter will always exist a setter and viceversa.
      // get method annotation and check if it exists as expected
      final Object res;

      final Method getter = typeRef.getMethod(method.getName());

      final Property property = ClassUtils.getAnnotation(Property.class, getter);
      if (property == null) {
        final NavigationProperty navProp = ClassUtils.getAnnotation(NavigationProperty.class, getter);
        if (navProp == null) {
          throw new UnsupportedOperationException("Unsupported method " + method.getName());
        } else {
          // if the getter refers to a navigation property ... navigate and follow link if necessary
          res = getNavigationPropertyValue(navProp, getter);
        }
      } else {
        // if the getter refers to a property .... get property from wrapped entity
        res = getPropertyValue(property, getter.getGenericReturnType());
      }

      // attach the current handler
      attach();

      return res;
    } else if (method.getName().startsWith("set")) {
      // get the corresponding getter method (see assumption above)
      final String getterName = method.getName().replaceFirst("set", "get");
      final Method getter = typeRef.getMethod(getterName);

      final Property property = ClassUtils.getAnnotation(Property.class, getter);
      if (property == null) {
        final NavigationProperty navProp = ClassUtils.getAnnotation(NavigationProperty.class, getter);
        if (navProp == null) {
          throw new UnsupportedOperationException("Unsupported method " + method.getName());
        } else {
          // if the getter refers to a navigation property ... 
          if (ArrayUtils.isEmpty(args) || args.length != 1) {
            throw new IllegalArgumentException("Invalid argument");
          }

          setNavigationPropertyValue(navProp, args[0]);
        }
      } else {
        setPropertyValue(property, args[0]);
      }

      return ClassUtils.returnVoid();
    } else if (method.getName().startsWith("new")) {
      // get the corresponding getter method (see assumption above)
      final String getterName = method.getName().replaceFirst("new", "get");
      final Method getter = typeRef.getMethod(getterName);

      final Property property = ClassUtils.getAnnotation(Property.class, getter);
      if (property == null) {
        throw new UnsupportedOperationException("Unsupported method " + method.getName());
      }

      return newComplex(property.name(), getter.getReturnType());
    } else {
      throw new UnsupportedOperationException("Method not found: " + method);
    }
  }

  protected void attach() {
    if (!entityContext.isAttached(targetHandler)) {
      entityContext.attach(targetHandler, AttachedEntityStatus.ATTACHED);
    }
  }

  protected void attach(final AttachedEntityStatus status) {
    attach(status, true);
  }

  protected void attach(final AttachedEntityStatus status, final boolean override) {
    if (entityContext.isAttached(targetHandler)) {
      if (override) {
        entityContext.setStatus(targetHandler, status);
      }
    } else {
      entityContext.attach(targetHandler, status);
    }
  }

  @SuppressWarnings({"unchecked"})
  protected <NE> NE newComplex(final String propertyName, final Class<NE> reference) {
    final Class<?> complexTypeRef;
    final boolean isCollection;
    if (Collection.class.isAssignableFrom(reference)) {
      complexTypeRef = ClassUtils.extractTypeArg(reference);
      isCollection = true;
    } else {
      complexTypeRef = reference;
      isCollection = false;
    }

    final ComplexType annotation = complexTypeRef.getAnnotation(ComplexType.class);
    if (annotation == null) {
      throw new IllegalArgumentException("Invalid complex type " + complexTypeRef);
    }

    final FullQualifiedName typeName =
            new FullQualifiedName(ClassUtils.getNamespace(complexTypeRef), annotation.name());

    final ODataComplexValue<? extends CommonODataProperty> complex =
            client.getObjectFactory().newComplexValue(typeName.toString());

    final ComplexTypeInvocationHandler<?> handler =
            ComplexTypeInvocationHandler.getInstance(complex, complexTypeRef, targetHandler);

    if (isCollection) {
      Object value = propertyChanges.get(propertyName);

      if (value == null) {
        value = new ArrayList<ComplexTypeInvocationHandler<?>>();
        propertyChanges.put(propertyName, value);
      }

      ((Collection<ComplexTypeInvocationHandler<?>>) value).add(handler);
    } else {
      propertyChanges.put(propertyName, handler);
    }

    attach(AttachedEntityStatus.CHANGED);

    return (NE) Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {complexTypeRef},
            handler);
  }

  private Object getNavigationPropertyValue(final NavigationProperty property, final Method getter) {
    if (!(internal instanceof ODataLinked)) {
      throw new UnsupportedOperationException("Internal object is not navigable");
    }

    final Class<?> type = getter.getReturnType();
    final Class<?> collItemType;
    if (AbstractEntityCollection.class.isAssignableFrom(type)) {
      final Type[] entityCollectionParams =
              ((ParameterizedType) type.getGenericInterfaces()[0]).getActualTypeArguments();
      collItemType = (Class<?>) entityCollectionParams[0];
    } else {
      collItemType = type;
    }

    final Object navPropValue;

    if (linkChanges.containsKey(property)) {
      navPropValue = linkChanges.get(property);
    } else {
      final ODataLink link = ((ODataLinked) internal).getNavigationLink(property.name());
      if (link instanceof ODataInlineEntity) {
        // return entity
        navPropValue = getEntityProxy(
                ((ODataInlineEntity) link).getEntity(),
                property.targetContainer(),
                property.targetEntitySet(),
                type,
                false);
      } else if (link instanceof ODataInlineEntitySet) {
        // return entity set
        navPropValue = getEntityCollection(
                collItemType,
                type,
                property.targetContainer(),
                ((ODataInlineEntitySet) link).getEntitySet(),
                link.getLink(),
                false);
      } else {
        // navigate
        final URI uri = URIUtils.getURI(
                containerHandler.getFactory().getServiceRoot(), link.getLink().toASCIIString());

        if (AbstractEntityCollection.class.isAssignableFrom(type)) {
          navPropValue = getEntityCollection(
                  collItemType,
                  type,
                  property.targetContainer(),
                  client.getRetrieveRequestFactory().getEntitySetRequest(uri).execute().getBody(),
                  uri,
                  true);
        } else {
          final ODataRetrieveResponse<CommonODataEntity> res =
                  client.getRetrieveRequestFactory().getEntityRequest(uri).execute();

          navPropValue = getEntityProxy(
                  res.getBody(),
                  property.targetContainer(),
                  property.targetEntitySet(),
                  type,
                  res.getETag(),
                  true);
        }
      }

      if (navPropValue != null) {
        int checkpoint = linkChanges.hashCode();
        linkChanges.put(property, navPropValue);
        updateLinksTag(checkpoint);
      }
    }

    return navPropValue;
  }

  protected abstract Object getPropertyValue(final String name, final Type type);

  private Object getPropertyValue(final Property property, final Type type) {
    return getPropertyValue(property.name(), type);
  }

  public Object getAdditionalProperty(final String name) {
    return getPropertyValue(name, null);
  }

  public abstract Collection<String> getAdditionalPropertyNames();

  private void setNavigationPropertyValue(final NavigationProperty property, final Object value) {
    // 1) attach source entity
    if (!entityContext.isAttached(targetHandler)) {
      entityContext.attach(targetHandler, AttachedEntityStatus.CHANGED);
    }

    // 2) attach the target entity handlers
    for (Object link : AbstractEntityCollection.class.isAssignableFrom(value.getClass())
            ? (AbstractEntityCollection) value : Collections.singleton(value)) {

      final InvocationHandler etih = Proxy.getInvocationHandler(link);
      if (!(etih instanceof EntityTypeInvocationHandler)) {
        throw new IllegalArgumentException("Invalid argument type");
      }

      @SuppressWarnings("unchecked")
      final EntityTypeInvocationHandler<C> targetHandler = (EntityTypeInvocationHandler<C>) etih;
      if (!targetHandler.getTypeRef().isAnnotationPresent(EntityType.class)) {
        throw new IllegalArgumentException("Invalid argument type " + targetHandler.getTypeRef().getSimpleName());
      }

      if (!entityContext.isAttached(targetHandler)) {
        entityContext.attach(targetHandler, AttachedEntityStatus.LINKED);
      }
    }

    // 3) add links
    linkChanges.put(property, value);
  }

  protected abstract void setPropertyValue(final Property property, final Object value);

  public void addAdditionalProperty(final String name, final Object value) {
    propertyChanges.put(name, value);
    if (!entityContext.isAttached(targetHandler)) {
      entityContext.attach(targetHandler, AttachedEntityStatus.CHANGED);
    }
  }

  protected void updatePropertiesTag(final int checkpoint) {
    if (checkpoint == propertiesTag) {
      propertiesTag = propertyChanges.hashCode();
    }
  }

  private void updateLinksTag(final int checkpoint) {
    if (checkpoint == linksTag) {
      linksTag = linkChanges.hashCode();
    }
  }

  public abstract boolean isChanged();
}
