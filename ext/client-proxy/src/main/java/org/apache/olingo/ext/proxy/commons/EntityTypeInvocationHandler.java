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

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.client.api.CommonEdmEnabledODataClient;
import org.apache.olingo.client.api.communication.request.retrieve.ODataMediaRequest;
import org.apache.olingo.client.core.uri.URIUtils;
import org.apache.olingo.commons.api.domain.CommonODataEntity;
import org.apache.olingo.commons.api.domain.CommonODataProperty;
import org.apache.olingo.commons.api.domain.ODataLinked;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.format.ODataMediaFormat;
import org.apache.olingo.ext.proxy.api.annotations.EntityType;
import org.apache.olingo.ext.proxy.api.annotations.Property;
import org.apache.olingo.ext.proxy.context.AttachedEntityStatus;
import org.apache.olingo.ext.proxy.context.EntityUUID;
import org.apache.olingo.ext.proxy.utils.EngineUtils;

public class EntityTypeInvocationHandler<C extends CommonEdmEnabledODataClient<?>>
        extends AbstractTypeInvocationHandler<C> {

  private static final long serialVersionUID = 2629912294765040037L;

  private CommonODataEntity entity;

  private Map<String, InputStream> streamedPropertyChanges = new HashMap<String, InputStream>();

  private InputStream stream;

  private EntityUUID uuid;

  static EntityTypeInvocationHandler<?> getInstance(
          final CommonODataEntity entity,
          final EntitySetInvocationHandler<?, ?, ?, ?> entitySet,
          final Class<?> typeRef) {

    return getInstance(
            entity,
            entitySet.getEntitySetName(),
            typeRef,
            entitySet.containerHandler);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static EntityTypeInvocationHandler<?> getInstance(
          final CommonODataEntity entity,
          final String entitySetName,
          final Class<?> typeRef,
          final EntityContainerInvocationHandler<?> containerHandler) {

    return new EntityTypeInvocationHandler(
            entity, entitySetName, typeRef, containerHandler);
  }

  private EntityTypeInvocationHandler(
          final CommonODataEntity entity,
          final String entitySetName,
          final Class<?> typeRef,
          final EntityContainerInvocationHandler<C> containerHandler) {

    super(containerHandler.getClient(), typeRef, (ODataLinked) entity, containerHandler);

    this.entity = entity;
    this.entity.setMediaEntity(typeRef.getAnnotation(EntityType.class).hasStream());

    this.uuid = new EntityUUID(
            containerHandler.getEntityContainerName(),
            entitySetName,
            entity.getTypeName(),
            EngineUtils.getKey(client.getCachedEdm(), typeRef, entity));

    this.stream = null;
  }

  public void setEntity(final CommonODataEntity entity) {
    this.entity = entity;
    this.entity.setMediaEntity(typeRef.getAnnotation(EntityType.class).hasStream());

    this.uuid = new EntityUUID(
            getUUID().getContainerName(),
            getUUID().getEntitySetName(),
            getUUID().getName(),
            EngineUtils.getKey(client.getCachedEdm(), typeRef, entity));

    this.propertyChanges.clear();
    this.linkChanges.clear();
    this.streamedPropertyChanges.clear();
    this.propertiesTag = 0;
    this.linksTag = 0;
    this.stream = null;
  }

  public EntityUUID getUUID() {
    return uuid;
  }

  @Override
  public FullQualifiedName getName() {
    return this.entity.getTypeName();
  }

  public String getEntityContainerName() {
    return uuid.getContainerName();
  }

  public String getEntitySetName() {
    return uuid.getEntitySetName();
  }

  public CommonODataEntity getEntity() {
    return entity;
  }

  /**
   * Gets the current ETag defined into the wrapped entity.
   *
   * @return
   */
  public String getETag() {
    return this.entity.getETag();
  }

  /**
   * Overrides ETag value defined into the wrapped entity.
   *
   * @param eTag ETag.
   */
  public void setETag(final String eTag) {
    this.entity.setETag(eTag);
  }

  @Override
  protected Object getPropertyValue(final String name, final Type type) {
    try {
      final Object res;
      
      final CommonODataProperty property = entity.getProperty(name);
      
      if (propertyChanges.containsKey(name)) {
        res = propertyChanges.get(name);
      } else if (property.hasComplexValue()) {
        res = newComplex(name, (Class<?>) type);
        EngineUtils.populate(
                client.getCachedEdm(), 
                res, 
                (Class<?>) type, 
                Property.class, 
                property.getValue().asComplex().iterator());
      } else {

        res = type == null
                ? EngineUtils.getValueFromProperty(client.getCachedEdm(), property)
                : EngineUtils.getValueFromProperty(client.getCachedEdm(), property, type);

        if (res != null) {
          int checkpoint = propertyChanges.hashCode();
          propertyChanges.put(name, res);
          updatePropertiesTag(checkpoint);
        }
      }

      return res;
    } catch (Exception e) {
      throw new IllegalArgumentException("Error getting value for property '" + name + "'", e);
    }
  }

  @Override
  public Collection<String> getAdditionalPropertyNames() {
    final Set<String> res = new HashSet<String>(propertyChanges.keySet());
    final Set<String> propertyNames = new HashSet<String>();
    for (Method method : typeRef.getMethods()) {
      final Annotation ann = method.getAnnotation(Property.class);
      if (ann != null) {
        final String property = ((Property) ann).name();
        propertyNames.add(property);

        // maybe someone could add a normal attribute to the additional set
        res.remove(property);
      }
    }

    for (CommonODataProperty property : entity.getProperties()) {
      if (!propertyNames.contains(property.getName())) {
        res.add(property.getName());
      }
    }

    return res;
  }

  @Override
  protected void setPropertyValue(final Property property, final Object value) {
    if (property.type().equalsIgnoreCase("Edm.Stream")) {
      setStreamedProperty(property, (InputStream) value);
    } else {
      propertyChanges.put(property.name(), value);
    }

    attach(AttachedEntityStatus.CHANGED);
  }

  @Override
  public boolean isChanged() {
    return this.linkChanges.hashCode() != this.linksTag
            || this.propertyChanges.hashCode() != this.propertiesTag
            || this.stream != null
            || !this.streamedPropertyChanges.isEmpty();
  }

  public void setStream(final InputStream stream) {
    if (typeRef.getAnnotation(EntityType.class).hasStream()) {
      IOUtils.closeQuietly(this.stream);
      this.stream = stream;
      attach(AttachedEntityStatus.CHANGED);
    }
  }

  public InputStream getStreamChanges() {
    return this.stream;
  }

  public Map<String, InputStream> getStreamedPropertyChanges() {
    return streamedPropertyChanges;
  }

  public InputStream getStream() {

    final URI contentSource = entity.getMediaContentSource();

    if (this.stream == null
            && typeRef.getAnnotation(EntityType.class).hasStream()
            && contentSource != null) {

      final String contentType =
              StringUtils.isBlank(entity.getMediaContentType()) ? "*/*" : entity.getMediaContentType();

      final ODataMediaRequest retrieveReq = client.getRetrieveRequestFactory().getMediaRequest(contentSource);
      retrieveReq.setFormat(ODataMediaFormat.fromFormat(contentType));

      this.stream = retrieveReq.execute().getBody();
    }

    return this.stream;
  }

  public Object getStreamedProperty(final Property property) {

    InputStream res = streamedPropertyChanges.get(property.name());

    try {
      if (res == null) {
        final URI link = URIUtils.getURI(
                containerHandler.getFactory().getServiceRoot(),
                EngineUtils.getEditMediaLink(property.name(), this.entity).toASCIIString());

        final ODataMediaRequest req = client.getRetrieveRequestFactory().getMediaRequest(link);
        res = req.execute().getBody();

      }
    } catch (Exception e) {
      res = null;
    }

    return res;

  }

  private void setStreamedProperty(final Property property, final InputStream input) {
    final Object obj = propertyChanges.get(property.name());
    if (obj != null && obj instanceof InputStream) {
      IOUtils.closeQuietly((InputStream) obj);
    }

    streamedPropertyChanges.put(property.name(), input);
  }

  @Override
  public String toString() {
    return uuid.toString();
  }

  @Override
  public int hashCode() {
    return uuid.hashCode();
  }

  @Override
  public boolean equals(final Object obj) {
    return obj instanceof EntityTypeInvocationHandler
            && ((EntityTypeInvocationHandler) obj).getUUID().equals(uuid);
  }
}
