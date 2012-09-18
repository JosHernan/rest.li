/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.docgen;

import com.linkedin.data.DataMap;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.data.schema.DataSchemaResolver;
import com.linkedin.data.schema.RecordDataSchema;
import com.linkedin.data.schema.generator.SchemaSampleDataGenerator;
import com.linkedin.data.template.DataTemplate;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.data.template.JacksonDataTemplateCodec;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.transform.patch.request.PatchCreator;
import com.linkedin.jersey.api.uri.UriBuilder;
import com.linkedin.r2.message.MessageBuilder;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.restli.common.BatchRequest;
import com.linkedin.restli.common.CollectionRequest;
import com.linkedin.restli.common.HttpMethod;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.common.PatchRequest;
import com.linkedin.restli.common.ResourceMethod;
import com.linkedin.restli.common.RestConstants;
import com.linkedin.restli.internal.server.ResourceContextImpl;
import com.linkedin.restli.internal.server.RestLiInternalException;
import com.linkedin.restli.internal.server.RestLiResponseHandler;
import com.linkedin.restli.internal.server.RoutingResult;
import com.linkedin.restli.internal.server.model.ResourceMethodDescriptor;
import com.linkedin.restli.internal.server.model.ResourceModel;
import com.linkedin.restli.internal.server.util.RestLiSyntaxException;
import com.linkedin.restli.restspec.ActionSchema;
import com.linkedin.restli.restspec.AssocKeySchema;
import com.linkedin.restli.restspec.AssociationSchema;
import com.linkedin.restli.restspec.CollectionSchema;
import com.linkedin.restli.restspec.EntitySchema;
import com.linkedin.restli.restspec.FinderSchema;
import com.linkedin.restli.restspec.ParameterSchema;
import com.linkedin.restli.restspec.ParameterSchemaArray;
import com.linkedin.restli.restspec.ResourceSchema;
import com.linkedin.restli.restspec.RestMethodSchema;
import com.linkedin.restli.restspec.RestSpecCodec;
import com.linkedin.restli.server.BatchCreateResult;
import com.linkedin.restli.server.BatchResult;
import com.linkedin.restli.server.BatchUpdateResult;
import com.linkedin.restli.server.CollectionResult;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.ResourceLevel;
import com.linkedin.restli.server.RestLiServiceException;
import com.linkedin.restli.server.UpdateResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.codehaus.jackson.impl.DefaultPrettyPrinter;

/**
 * Generates a example requests for a given Resource IDL. Uses
 * {@link SchemaSampleDataGenerator} to build example data models for input/output.
 *
 * @author dellamag, Keren Jin
 */
public class RestLiExampleGenerator
{
  static class RequestGenerationSpec
  {
    public boolean includeOptionalParameters = true;
    public boolean useDefaultValues = true;
  }

  /**
   * @param resourceSchemas resource schemas created with {@code rootResources}
   * @param rootResources root resources from its resource path. subresource models will be discovered
   * @param schemaResolver resolver that resolves related {@link ResourceSchema}
   */
  public RestLiExampleGenerator(ResourceSchemaCollection resourceSchemas,
                                Map<String, ResourceModel> rootResources,
                                DataSchemaResolver schemaResolver)
  {
    _resourceSchemas = resourceSchemas;
    _allResources = new TreeMap<String, ResourceModel>(rootResources);
    _schemaResolver = schemaResolver;
    _schemaClassLoader = Thread.currentThread().getContextClassLoader();

    for (ResourceModel model: rootResources.values())
    {
      buildSubModels(model.getName(), model.getSubResources());
    }
  }

  /**
   * Generate request-response pair with example data for REST method characterized by the specified specification
   *
   * @param resourceSchema schema of the subject resource
   * @param restMethodSchema schema of the subject REST method
   * @param spec specification of the generated example data
   * @return request-response pair that captures the generated example data
   */
  public RequestResponsePair generateRestMethodExample(ResourceSchema resourceSchema,
                                                       RestMethodSchema restMethodSchema,
                                                       RequestGenerationSpec spec)
  {
    final ResourceMethod restMethod = ResourceMethod.valueOf(restMethodSchema.getMethod().toUpperCase());

    String path = getMethodPath(resourceSchema,
                                COLLECTION_METHODS.contains(restMethod) ? ResourceLevel.COLLECTION : ResourceLevel.ENTITY);
    if (path == null)
    {
      return null;
    }

    // use $ instead of {} to create a valid URI
    path = path.replaceAll("\\{|\\}", "\\$");
    final UriBuilder uriBuilder = UriBuilder.fromPath(path);

    final ResourceModel model = _allResources.get(resourceSchema.getPath());
    // TODO OFFLINE RestLiResponseHandler requires RoutingResult, which requires ResourceMethodDescriptor.
    final ResourceMethodDescriptor method = model.findMethod(restMethod);

    final ParameterSchemaArray params = restMethodSchema.getParameters();
    if (params != null)
    {
      addParamsToUri(params, spec, uriBuilder);
    }

    final Class<? extends RecordTemplate> valueClass = getClass(resourceSchema.getSchema());

    final Double randomId = Math.random() * 100;
    final RecordTemplate record = buildRecordTemplate(valueClass);
    final CreateResponse cr = new CreateResponse(randomId);
    final UpdateResponse ur = new UpdateResponse(HttpStatus.S_200_OK);

    final RecordTemplate diffRecord = buildRecordTemplate(valueClass);
    final PatchRequest patch = new PatchRequest(PatchCreator.diff(record, diffRecord).getDataMap());

    final DataMap batchData = new DataMap();
    batchData.put(randomId.toString(), record.data());

    final Map<Double, UpdateResponse> buResponseData = new HashMap<Double, UpdateResponse>();
    buResponseData.put(randomId, ur);
    final BatchUpdateResult bur = new BatchUpdateResult<Double, RecordTemplate>(buResponseData);

    RecordTemplate requestEntity = null;
    Object responseEntity = null;
    switch (restMethod)
    {
      case GET:
        responseEntity = record;
        break;
      case CREATE:
        requestEntity = record;
        responseEntity = cr;
        break;
      case UPDATE:
        requestEntity = record;
        responseEntity = ur;
        break;
      case DELETE:
        responseEntity = ur;
        break;
      case PARTIAL_UPDATE:
        requestEntity = patch;
        responseEntity = ur;
        break;
      case BATCH_GET:
        final Map<Double, RecordTemplate> bgResponseData = new HashMap<Double, RecordTemplate>();
        bgResponseData.put(randomId, record);
        responseEntity = new BatchResult<Double, RecordTemplate>(bgResponseData, new HashMap<Double, RestLiServiceException>());
        break;
      case BATCH_CREATE:
        @SuppressWarnings({"unchecked"})
        final CollectionRequest bcRequest = new CollectionRequest(batchData, valueClass);
        requestEntity = bcRequest;
        responseEntity = new BatchCreateResult<Long, RecordTemplate>(Arrays.asList(cr));
        break;
      case BATCH_UPDATE:
        @SuppressWarnings("unchecked")
        final BatchRequest buRequest = new BatchRequest(batchData, valueClass);
        requestEntity = buRequest;
        responseEntity = bur;
        break;
      case BATCH_DELETE:
        final Map<Double, UpdateResponse> bdResponseData = new HashMap<Double, UpdateResponse>();
        bdResponseData.put(randomId, ur);
        responseEntity = new BatchUpdateResult<Double, RecordTemplate>(bdResponseData);
        break;
      case BATCH_PARTIAL_UPDATE:
        final DataMap batchPartialData = new DataMap();
        batchData.put(randomId.toString(), patch.data());
        requestEntity = new BatchRequest<PatchRequest>(batchPartialData, PatchRequest.class);
        responseEntity = bur;
        break;
    }

    return doRequest(uriBuilder,
                     requestEntity,
                     responseEntity,
                     restMethod.getHttpMethod(),
                     method);
  }

  /**
   * Generate request-response pair with example data for finder characterized by the specified specification
   *
   * @param resourceSchema schema of the subject resource
   * @param finderSchema schema of the subject finder
   * @param spec specification of the generated example data
   * @return request-response pair that captures the generated example data
   */
  public RequestResponsePair generateFinderExample(ResourceSchema resourceSchema,
                                                   FinderSchema finderSchema,
                                                   RequestGenerationSpec spec)
  {
    String path = getMethodPath(resourceSchema, ResourceLevel.COLLECTION);
    if (path == null)
    {
      return null;
    }

    // use $ instead of {} to create a valid URI
    path = path.replaceAll("\\{|\\}", "\\$");
    final UriBuilder uriBuilder = UriBuilder.fromPath(path);

    final ResourceModel model = _allResources.get(resourceSchema.getPath());
    // TODO OFFLINE RestLiResponseHandler requires RoutingResult, which requires ResourceMethodDescriptor.
    final ResourceMethodDescriptor method = model.findNamedMethod(finderSchema.getName());

    final Class<? extends RecordTemplate> valueClass = getClass(resourceSchema.getSchema());
    final Object responseEntity = buildFinderResponse(valueClass, method.getFinderMetadataType());
    uriBuilder.queryParam(RestConstants.QUERY_TYPE_PARAM, finderSchema.getName());

    final ParameterSchemaArray params = finderSchema.getParameters();
    if (params != null)
    {
      addParamsToUri(params, spec, uriBuilder);
    }

    final AssociationSchema assocSchema = resourceSchema.getAssociation();
    if (assocSchema != null)
    {
      final String singleAssocKeyName = finderSchema.getAssocKey();
      final Set<String> multipleAssocKeyNames = (finderSchema.hasAssocKeys() ? new HashSet<String>(finderSchema.getAssocKeys()) : null);
      if (singleAssocKeyName != null || multipleAssocKeyNames != null)
      {
        final UriBuilder assocKeyBuilder = new UriBuilder();
        for (AssocKeySchema assocKey: assocSchema.getAssocKeys())
        {
          if (singleAssocKeyName != null && singleAssocKeyName.equals(assocKey.getName()))
          {
            addAssocKeyToUri(assocKey, assocKeyBuilder);
            break;
          }

          if (multipleAssocKeyNames != null && multipleAssocKeyNames.contains(assocKey.getName()))
          {
            addAssocKeyToUri(assocKey, assocKeyBuilder);
          }
        }
        uriBuilder.path(assocKeyBuilder.build().getQuery());
      }
    }

    return doRequest(uriBuilder,
                     null,
                     responseEntity,
                     HttpMethod.GET,
                     method);
  }

  /**
   * Generate request-response pair with example data for action characterized by the specified specification
   *
   * @param resourceSchema schema of the subject resource
   * @param actionSchema schema of the subject action
   * @param resourceLevel resource level of the action
   * @param spec specification of the generated example data
   * @return request-response pair that captures the generated example data
   */
  public RequestResponsePair generateActionExample(ResourceSchema resourceSchema,
                                                   ActionSchema actionSchema,
                                                   ResourceLevel resourceLevel,
                                                   RequestGenerationSpec spec)
  {
    String path = getMethodPath(resourceSchema, resourceLevel);
    if (path == null)
    {
      return null;
    }

    // use $ instead of {} to create a valid URI
    path = path.replaceAll("\\{|\\}", "\\$");
    final UriBuilder uriBuilder = UriBuilder.fromPath(path);

    uriBuilder.queryParam(RestConstants.ACTION_PARAM, actionSchema.getName());

    final ResourceModel model = _allResources.get(resourceSchema.getPath());
    // TODO OFFLINE RestLiResponseHandler requires RoutingResult, which requires ResourceMethodDescriptor.
    final ResourceMethodDescriptor method = model.findActionMethod(actionSchema.getName(), resourceLevel);

    final DataMap requestEntity = new DataMap();
    Object responseEntity = null;

    if (actionSchema.hasParameters())
    {
      for (ParameterSchema param : actionSchema.getParameters())
      {
        assert(!param.hasItems());
        final DataSchema typeSchema = RestSpecCodec.textToSchema(param.getType(), _schemaResolver);
        final Object value = SchemaSampleDataGenerator.buildDataMappable(typeSchema, _defaultSpec);
        requestEntity.put(param.getName(), value);
      }
    }

    if (actionSchema.hasReturns())
    {
      final DataSchema returnsSchema = RestSpecCodec.textToSchema(actionSchema.getReturns(), _schemaResolver);
      responseEntity = SchemaSampleDataGenerator.buildDataMappable(returnsSchema, _defaultSpec);

      final Class<?> returnsClass = method.getActionReturnType();
      if (DataTemplate.class.isAssignableFrom(returnsClass))
        responseEntity = DataTemplateUtil.wrap(responseEntity, returnsClass.asSubclass(DataTemplate.class));
    }

    return doRequest(uriBuilder,
                     requestEntity,
                     responseEntity,
                     HttpMethod.POST,
                     method);
  }

  private static <R extends RecordTemplate> R buildRecordTemplate(Class<R> recordClass)
  {
    return buildRecordTemplate(recordClass, _defaultSpec);
  }

  private static <R extends RecordTemplate> R buildRecordTemplate(Class<R> recordClass,
                                                                  SchemaSampleDataGenerator.DataGenerationSpec spec)
  {
    final DataSchema schema = DataTemplateUtil.getSchema(recordClass);
    if (schema == null || !(schema instanceof RecordDataSchema))
    {
      return null;
    }

    final DataMap data = SchemaSampleDataGenerator.buildRecordData((RecordDataSchema) schema, spec);
    return DataTemplateUtil.wrap(data, recordClass);
  }

  private static String getMethodPath(ResourceSchema resourceSchema, ResourceLevel resourceLevel)
  {
    if (resourceLevel == ResourceLevel.COLLECTION)
    {
      return resourceSchema.getPath();
    }
    else
    {
      final CollectionSchema collection = resourceSchema.getCollection();
      final AssociationSchema association = resourceSchema.getAssociation();
      EntitySchema entity = null;
      if (collection != null)
      {
        entity = collection.getEntity();
      }
      else if (association != null)
      {
        entity = association.getEntity();
      }

      if (entity == null)
      {
        return null;
      }

      return entity.getPath();
    }
  }

  private static <R extends RecordTemplate> CollectionResult<R, ? extends RecordTemplate>
  buildFinderResponse(Class<R> valueClass, Class<? extends RecordTemplate> metadataClass)
  {
    final List<R> results = new ArrayList<R>();
    final int count = (int)(Math.random() * 3) + 1;
    for (int i = 0; i < count; i++)
    {
      final R item = buildRecordTemplate(valueClass);
      results.add(item);
    }

    RecordTemplate metadata = null;
    if (metadataClass != null && Math.random() > 0.5)
    {
      @SuppressWarnings("unchecked")
      final RecordTemplate newMetadata = buildRecordTemplate(metadataClass);
      metadata = newMetadata;
    }

    return new CollectionResult<R, RecordTemplate>(results, count, metadata);
  }

  private static void writeEntity(Object entity, MessageBuilder<?> messageBuilder)
  {
    if (entity == null)
    {
      return;
    }

    try
    {
      if (entity instanceof DataMap)
      {
        messageBuilder.setEntity(_codec.mapToBytes((DataMap) entity));
      }
      else
      {
        messageBuilder.setEntity(_codec.dataTemplateToBytes((RecordTemplate)entity, true));
      }
    }
    catch (IOException e)
    {
      throw new RestLiInternalException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private <T> Class<T> getClass(String className)
  {
    try
    {
      return (Class<T>) _schemaClassLoader.loadClass(className);
    }
    catch (ClassNotFoundException e)
    {
      throw new RestLiInternalException(String.format("Expected to find class in classpath: %s", className));
    }
  }

  private void buildSubModels(String schemaNamePrefix, Iterable<ResourceModel> models)
  {
    for (ResourceModel model: models)
    {
      final String schemaName = schemaNamePrefix + "." + model.getName();
      final ResourceSchema schema = _resourceSchemas.getResource(schemaName);
      assert(schema != null);
      _allResources.put(schema.getPath(), model);
      buildSubModels(schemaName, model.getSubResources());
    }
  }

  private void addParamsToUri(ParameterSchemaArray params, RequestGenerationSpec spec, UriBuilder uriBuilder)
  {
    for (ParameterSchema param: params)
    {
      if (param.hasOptional() && param.isOptional() && !spec.includeOptionalParameters)
      {
        continue;
      }

      final List<Object> paramValues = new ArrayList<Object>();
      if (spec.useDefaultValues && param.hasDefault())
      {
        paramValues.add(param.getDefault());
      }
      else if (param.hasItems())
      {
        final DataSchema itemsSchema = RestSpecCodec.textToSchema(param.getItems(), _schemaResolver);
        final int valueCount = (int)(Math.random() * 3) + 1;
        for (int i = 0; i < valueCount; ++i)
        {
          paramValues.add(SchemaSampleDataGenerator.buildDataMappable(itemsSchema, _defaultSpec));
        }
      }
      else
      {
        final DataSchema typeSchema = RestSpecCodec.textToSchema(param.getType(), _schemaResolver);
        paramValues.add(SchemaSampleDataGenerator.buildDataMappable(typeSchema, _defaultSpec));
      }

      for (Object value: paramValues)
      {
        uriBuilder.queryParam(param.getName(), value.toString());
      }
    }
  }

  private void addAssocKeyToUri(AssocKeySchema assocKey, UriBuilder uriBuilder)
  {
    final DataSchema typeSchema = RestSpecCodec.textToSchema(assocKey.getType(), _schemaResolver);
    final Object exampleValue = SchemaSampleDataGenerator.buildDataMappable(typeSchema, _defaultSpec);
    uriBuilder.queryParam(assocKey.getName(), exampleValue);
  }

  /**
   * Uses {@link RestLiResponseHandler} to process the given request
   */
  private RequestResponsePair doRequest(UriBuilder uriBuilder,
                                        Object requestEntity,
                                        Object responseEntity,
                                        HttpMethod httpMethod,
                                        ResourceMethodDescriptor method)
  {
    final RestRequestBuilder requestBuilder = new RestRequestBuilder(uriBuilder.build());
    requestBuilder.setMethod(httpMethod.name());
    writeEntity(requestEntity, requestBuilder);
    final RestRequest request = requestBuilder.build();

    final RestResponse response;
    try
    {
      final RoutingResult routingResult = new RoutingResult(new ResourceContextImpl(), method);
      response = _responseHandler.buildResponse(request, routingResult, responseEntity);
    }
    catch (RestLiSyntaxException e)
    {
      throw new RestLiInternalException();
    }
    catch (IOException e)
    {
      throw new RestLiInternalException();
    }

    return new RequestResponsePair(request, response);
  }

  private static final SchemaSampleDataGenerator.DataGenerationSpec _defaultSpec = new SchemaSampleDataGenerator.DataGenerationSpec();
  private static final Set<ResourceMethod> COLLECTION_METHODS = new HashSet<ResourceMethod>();
  private static final JacksonDataTemplateCodec _codec = new JacksonDataTemplateCodec();
  static
  {
    COLLECTION_METHODS.add(ResourceMethod.CREATE);
    COLLECTION_METHODS.add(ResourceMethod.BATCH_GET);
    COLLECTION_METHODS.add(ResourceMethod.BATCH_CREATE);
    COLLECTION_METHODS.add(ResourceMethod.BATCH_DELETE);
    COLLECTION_METHODS.add(ResourceMethod.BATCH_UPDATE);
    COLLECTION_METHODS.add(ResourceMethod.BATCH_PARTIAL_UPDATE);

    _codec.setPrettyPrinter(new DefaultPrettyPrinter());
  }

  private final ResourceSchemaCollection _resourceSchemas;
  private final Map<String, ResourceModel> _allResources;
  private final RestLiResponseHandler _responseHandler = new RestLiResponseHandler();
  private final DataSchemaResolver _schemaResolver;
  private final ClassLoader _schemaClassLoader;
}
