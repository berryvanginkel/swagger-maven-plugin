package com.github.kongchen.swagger.docgen.reader;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.BeanParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.github.kongchen.swagger.docgen.LogAdapter;
import com.github.kongchen.swagger.docgen.jaxrs.BeanParamInjectParamExtention;
import com.github.kongchen.swagger.docgen.jaxrs.JaxrsParameterExtension;
import com.github.kongchen.swagger.docgen.spring.SpringSwaggerExtension;
import com.sun.jersey.api.core.InjectParam;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.AuthorizationScope;
import io.swagger.annotations.Extension;
import io.swagger.annotations.ExtensionProperty;
import io.swagger.annotations.ResponseHeader;
import io.swagger.converter.ModelConverters;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtensions;
import io.swagger.jersey.SwaggerJersey2Jaxrs;
import io.swagger.jersey.SwaggerJerseyJaxrs;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.Response;
import io.swagger.models.Scheme;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.AbstractSerializableParameter;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.FormParameter;
import io.swagger.models.parameters.HeaderParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.util.ParameterProcessor;

/**
 * Created by chekong on 15/4/28.
 */
public abstract class AbstractReader {
    protected final LogAdapter LOG;
    protected Swagger swagger;
    protected Set<Type> typesToSkip = new HashSet<Type>();

    public Set<Type> getTypesToSkip() {
        return typesToSkip;
    }

    public void setTypesToSkip(List<Type> typesToSkip) {
        this.typesToSkip = new HashSet<Type>(typesToSkip);
    }

    public void setTypesToSkip(Set<Type> typesToSkip) {
        this.typesToSkip = typesToSkip;
    }

    public void addTypeToSkippedTypes(Type type) {
        this.typesToSkip.add(type);
    }

    public AbstractReader(Swagger swagger, LogAdapter LOG) {
        this.swagger = swagger;
        this.LOG = LOG;
        updateExtensionChain();
    }

    private void updateExtensionChain() {
        List<SwaggerExtension> extensions = new ArrayList<SwaggerExtension>();
        if (this.getClass() == SpringMvcApiReader.class) {
            extensions.add(new SpringSwaggerExtension());
        } else {
            extensions.add(new BeanParamInjectParamExtention());
            extensions.add(new SwaggerJerseyJaxrs());
            extensions.add(new SwaggerJersey2Jaxrs());
            extensions.add(new JaxrsParameterExtension());
        }
        SwaggerExtensions.setExtensions(extensions);
    }

    protected List<SecurityRequirement> getSecurityRequirements(Api api) {
        int position = api.position();
        String produces = api.produces();
        String consumes = api.consumes();
        String schems = api.protocols();
        Authorization[] authorizations = api.authorizations();

        List<SecurityRequirement> securities = new ArrayList<SecurityRequirement>();
        for (Authorization auth : authorizations) {
            if (auth.value() != null && !"".equals(auth.value())) {
                SecurityRequirement security = new SecurityRequirement();
                security.setName(auth.value());
                AuthorizationScope[] scopes = auth.scopes();
                for (AuthorizationScope scope : scopes) {
                    if (scope.scope() != null && !"".equals(scope.scope())) {
                        security.addScope(scope.scope());
                    }
                }
                securities.add(security);
            }
        }
        return securities;
    }

    protected String parseOperationPath(String operationPath, Map<String, String> regexMap) {
        
        // If the operation's path is a simple "/", don't bother parsing - just return it as is.
        if (operationPath.equalsIgnoreCase("/")) {
            return operationPath;
        }
        
        String[] pps = operationPath.split("/");
        String[] pathParts = new String[pps.length];


        for (int i = 0; i < pps.length; i++) {
            String p = pps[i];
            if (p.startsWith("{")) {
                int pos = p.indexOf(":");
                if (pos > 0) {
                    String left = p.substring(1, pos);
                    String right = p.substring(pos + 1, p.length() - 1);
                    pathParts[i] = "{" + left.trim() + "}";
                    regexMap.put(left.trim(), right);
                } else
                    pathParts[i] = p;
            } else pathParts[i] = p;
        }
        StringBuilder pathBuilder = new StringBuilder();
        for (String p : pathParts) {
            if (!p.isEmpty())
                pathBuilder.append("/").append(p);
        }
        operationPath = pathBuilder.toString();
        return operationPath;
    }

    protected void updateOperationParameters(List<Parameter> parentParameters, Map<String, String> regexMap, Operation operation) {
        if (parentParameters != null) {
            for (Parameter param : parentParameters) {
                operation.parameter(param);
            }
        }
        for (Parameter param : operation.getParameters()) {
            if (regexMap.get(param.getName()) != null) {
                String pattern = regexMap.get(param.getName());
                param.setPattern(pattern);
            }
        }
    }

    protected Map<String, Property> parseResponseHeaders(ResponseHeader[] headers) {
        Map<String, Property> responseHeaders = null;
        if (headers != null && headers.length > 0) {
            for (ResponseHeader header : headers) {
                String name = header.name();
                if (!"".equals(name)) {
                    if (responseHeaders == null)
                        responseHeaders = new HashMap<String, Property>();
                    String description = header.description();
                    Class<?> cls = header.response();
                    String container = header.responseContainer();

                    if (!cls.equals(Void.class) && !"void".equals(cls.toString())) {
                        Property responseProperty = null;
                        Property property = ModelConverters.getInstance().readAsProperty(cls);
                        if (property != null) {
                            if ("list".equalsIgnoreCase(container))
                                responseProperty = new ArrayProperty(property);
                            else if ("map".equalsIgnoreCase(container))
                                responseProperty = new MapProperty(property);
                            else
                                responseProperty = property;
                            responseProperty.setDescription(description);
                            responseHeaders.put(name, responseProperty);
                        }
                    }
                }
            }
        }
        return responseHeaders;
    }

    protected Set<Map<String, Object>> parseCustomExtensions(Extension[] extensions) {
        Set<Map<String, Object>> resultSet = new HashSet<Map<String, Object>>();
        if (extensions != null && extensions.length > 0) {
            for (Extension extension : extensions) {
                if (extension != null && extension.properties().length > 0) {
                    Map<String, Object> extensionProperties = null;
                    for (ExtensionProperty extensionProperty : extension.properties()) {
                        String name = extensionProperty.name();
                        if (!"".equals(name)) {
                            if (extensionProperties == null)
                                extensionProperties = new HashMap<String, Object>();
                            String value = extensionProperty.value();
                            extensionProperties.put(name, value);
                        }
                    }
                    Map<String, Object> wrapper;
                    if (extension.name() != null && extension.name().length() > 0) {
                        wrapper = new HashMap<String, Object>();
                        wrapper.put(extension.name(), extensionProperties);
                        resultSet.add(wrapper);
                    } else {
                        resultSet.add(extensionProperties);
                    }
                }
            }
        }
        return resultSet;
    }

    protected void updatePath(String operationPath, String httpMethod, Operation operation) {
        if (httpMethod == null) {
            return;
        }
        Path path = swagger.getPath(operationPath);
        if (path == null) {
            path = new Path();
            swagger.path(operationPath, path);
        }
        path.set(httpMethod, operation);
    }

    protected void updateTagsForOperation(Operation operation, ApiOperation op) {
        if (op != null) {
            boolean hasExplicitTag = false;
            for (String tag : op.tags()) {
                if (!"".equals(tag)) {
                    operation.tag(tag);
                    swagger.tag(new Tag().name(tag));
                }
            }
        }
    }

    protected boolean canReadApi(boolean readHidden, Api api) {
        return (api != null && readHidden) || (api != null && !api.hidden());
    }

    protected Set<Tag> extractTags(Api api) {
        Set<Tag> output = new LinkedHashSet<Tag>();

        boolean hasExplicitTags = false;
        for (String tag : api.tags()) {
            if (!"".equals(tag)) {
                hasExplicitTags = true;
                output.add(new Tag().name(tag));
            }
        }
        if (!hasExplicitTags) {
            // derive tag from api path + description
            String tagString = api.value().replace("/", "");
            if (!"".equals(tagString)) {
                Tag tag = new Tag().name(tagString);
                if (!"".equals(api.description()))
                    tag.description(api.description());
                output.add(tag);
            }
        }
        return output;
    }

    protected void updateOperationProtocols(ApiOperation apiOperation, Operation operation) {
        String protocols = apiOperation.protocols();
        if (!"".equals(protocols)) {
            String[] parts = protocols.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!"".equals(trimmed))
                    operation.scheme(Scheme.forValue(trimmed));
            }
        }
    }

    protected Map<String, Tag> updateTagsForApi(Map<String, Tag> parentTags, Api api) {
        // the value will be used as a tag for 2.0 UNLESS a Tags annotation is present
        Set<Tag> tags = extractTags(api);
        Map<String, Tag> tagsMap = new HashMap<String, Tag>();
        for (Tag tag : tags) {
            tagsMap.put(tag.getName(), tag);
        }
        if (parentTags != null)
            tagsMap.putAll(parentTags);
        for (Tag tag : tagsMap.values()) {
            swagger.tag(tag);
        }
        return tagsMap;
    }

    boolean isPrimitive(Class<?> cls) {
        boolean out = false;

        Property property = ModelConverters.getInstance().readAsProperty(cls);
        if (property == null)
            out = false;
        else if ("integer".equals(property.getType()))
            out = true;
        else if ("string".equals(property.getType()))
            out = true;
        else if ("number".equals(property.getType()))
            out = true;
        else if ("boolean".equals(property.getType()))
            out = true;
        else if ("array".equals(property.getType()))
            out = true;
        else if ("file".equals(property.getType()))
            out = true;
        return out;
    }

    protected void updateOperation(String[] apiConsumes, String[] apiProduces, Map<String, Tag> tags, List<SecurityRequirement> securities, Operation operation) {
        if (operation == null) {
            return;
        }
        if (operation.getConsumes() == null) {
            for (String mediaType : apiConsumes) {
                operation.consumes(mediaType);
            }
        }
        if (operation.getProduces() == null) {
            for (String mediaType : apiProduces) {
                operation.produces(mediaType);
            }
        }

        if (operation.getTags() == null) {
            for (String tagString : tags.keySet()) {
                operation.tag(tagString);
            }
        }
        for (SecurityRequirement security : securities) {
            operation.security(security);
        }
    }

    private boolean isApiParamHidden(List<Annotation> parameterAnnotations) {
        boolean isHidden = false;

        for (Annotation parameterAnnotation : parameterAnnotations) {
            if (parameterAnnotation instanceof ApiParam) {
                isHidden = ((ApiParam) parameterAnnotation).hidden();
                break;
            }
        }

        return isHidden;
    }

    private boolean hasValidAnnotations(List<Annotation> parameterAnnotations) {
        // Because method parameters can contain parameters that are valid, but 
        // not part of the API contract, first check to make sure the parameter 
        // has at lease one annotation before processing it.  Also, check a 
        // whitelist to make sure that the annotation of the parameter is 
        // compatible with spring-maven-plugin 

        if (parameterAnnotations.isEmpty()) {
            return false;
        }

        List<Type> validParameterAnnotations = new ArrayList<Type>();
        validParameterAnnotations.add(ModelAttribute.class);
        validParameterAnnotations.add(BeanParam.class);
        validParameterAnnotations.add(InjectParam.class);
        validParameterAnnotations.add(ApiParam.class);
        validParameterAnnotations.add(PathParam.class);
        validParameterAnnotations.add(QueryParam.class);
        validParameterAnnotations.add(HeaderParam.class);
        validParameterAnnotations.add(FormParam.class);
        validParameterAnnotations.add(RequestParam.class);
        validParameterAnnotations.add(RequestBody.class);
        validParameterAnnotations.add(PathVariable.class);
        validParameterAnnotations.add(RequestHeader.class);


        boolean hasValidAnnotation = false;
        for (Annotation potentialAnnotation : parameterAnnotations) {
            if (validParameterAnnotations.contains(potentialAnnotation.annotationType())) {
                hasValidAnnotation = true;
                break;
            }
        }

        return hasValidAnnotation;
    }

    protected List<Parameter> getParameters(Type type, List<Annotation> annotations) {

        if (hasValidAnnotations(annotations) == false) {
            return new ArrayList<Parameter>();
        }

        if (isApiParamHidden(annotations)) {
            return new ArrayList<Parameter>();
        }

        Iterator<SwaggerExtension> chain = SwaggerExtensions.chain();
        List<Parameter> parameters = new ArrayList<Parameter>();
        Class<?> cls = TypeUtils.getRawType(type, type);
        LOG.info("Looking for path/query/header/form/cookie params in " + cls);

        if (chain.hasNext()) {
            SwaggerExtension extension = chain.next();
            LOG.info("trying extension " + extension);
            parameters = extension.extractParameters(annotations, type, typesToSkip, chain);
        }

        if (parameters.size() > 0) {
            for (Parameter parameter : parameters) {
                ParameterProcessor.applyAnnotations(swagger, parameter, type, annotations);
                parameter = this.fixCollectionFormatForArrayTypes(cls, parameter);
            }
        } else {
            // look for body parameters
            LOG.info("Looking for body params in " + cls);
            if (typesToSkip.contains(type) == false) {
                Parameter param = ParameterProcessor.applyAnnotations(swagger, null, type, annotations);
                if (param != null) {
                    parameters.add(param);
                }
            }
        }
        return parameters;
    }

    private Parameter fixCollectionFormatForArrayTypes(Class<?> cls, Parameter parameter) {

        // This is a workaround until the following swagger-core bug is fixed:
        // https://github.com/swagger-api/swagger-core/issues/1160
        // The collectionFormat for array-typed items is returning as "csv", even
        // in cases where a csv string does not apply. In these cases, we need to
        // re-set the type back to "multi".

        if (parameter instanceof AbstractSerializableParameter) {
            final AbstractSerializableParameter<?> p = (AbstractSerializableParameter<?>) parameter;

            // Check to see if the if the parameter has items. If it does, it's an array type.
            // If the collectionFormat is "csv", and the java type is Collection or Array, we need to change it to "multi" and re-define the parameter.
            if (p.getItems() != null && p.getCollectionFormat() == "csv" && (Collection.class.isAssignableFrom(cls) || cls.isArray())) {
                p.collectionFormat("multi");
                parameter = p;
            }
        }

        return parameter;
    }

    protected void updateApiResponse(Operation operation, ApiResponses responseAnnotation) {        
        Class<?> responseClass;
        for (ApiResponse apiResponse : responseAnnotation.value()) {
            Map<String, Property> responseHeaders = parseResponseHeaders(apiResponse.responseHeaders());
            responseClass = apiResponse.response();
            Response response = new Response()
                    .description(apiResponse.message())
                    .headers(responseHeaders);

            if (responseClass == null || responseClass.equals(Void.class)) {
                if (operation.getResponses() != null && !operation.getResponses().isEmpty()) {
                    Response apiOperationResponse = operation.getResponses().get(String.valueOf(apiResponse.code()));
                    if (apiOperationResponse != null) {
                        response.setSchema(apiOperationResponse.getSchema());
                    }
                }
            }

            if (responseClass != null && !responseClass.equals(Void.class)) {
                Map<String, Model> models = ModelConverters.getInstance().read(responseClass);
                for (String key : models.keySet()) {
                    response.schema(new RefProperty().asDefault(key));
                    swagger.model(key, models.get(key));
                }
                models = ModelConverters.getInstance().readAll(responseClass);
                for (String key : models.keySet()) {
                    swagger.model(key, models.get(key));
                }
                
                if (response.getSchema() == null) {
                    Response apiOperationResponse = operation.getResponses().get(String.valueOf(apiResponse.code()));
                    if (apiOperationResponse != null) {
                        response.setSchema(apiOperationResponse.getSchema());
                    }
                }
            }
            
            if (apiResponse.code() == 0) {
                operation.defaultResponse(response);
            } else {
                operation.response(apiResponse.code(), response);
            }
        }
    }

    protected String[] updateOperationProduces(String[] parentProduces, String[] apiProduces, Operation operation) {

        if (parentProduces != null) {
            Set<String> both = new HashSet<String>(Arrays.asList(apiProduces));
            both.addAll(new HashSet<String>(Arrays.asList(parentProduces)));
            if (operation.getProduces() != null)
                both.addAll(new HashSet<String>(operation.getProduces()));
            apiProduces = both.toArray(new String[both.size()]);
        }
        return apiProduces;
    }

    protected String[] updateOperationConsumes(String[] parentConsumes, String[] apiConsumes, Operation operation) {

        if (parentConsumes != null) {
            Set<String> both = new HashSet<String>(Arrays.asList(apiConsumes));
            both.addAll(new HashSet<String>(Arrays.asList(parentConsumes)));
            if (operation.getConsumes() != null)
                both.addAll(new HashSet<String>(operation.getConsumes()));
            apiConsumes = both.toArray(new String[both.size()]);
        }
        return apiConsumes;
    }

    protected void readImplicitParameters(Method method, Operation operation) {
        ApiImplicitParams implicitParams = method.getAnnotation(ApiImplicitParams.class);
        if (implicitParams != null && implicitParams.value().length > 0) {
            for (ApiImplicitParam param : implicitParams.value()) {

                Class<?> cls = null;
                try {
                    cls = Class.forName(param.dataType());
                } catch (ClassNotFoundException e) {
                    cls = method.getDeclaringClass();
                }

                Parameter p = readImplicitParam(param, cls);
                if (p != null) {
                    operation.addParameter(p);
                }
            }
        }
    }

    protected Parameter readImplicitParam(ApiImplicitParam param, Class<?> apiClass) {
        Parameter p;
        if (param.paramType().equalsIgnoreCase("path")) {
            p = new PathParameter();
        } else if (param.paramType().equalsIgnoreCase("query")) {
            p = new QueryParameter();
        } else if (param.paramType().equalsIgnoreCase("form") || param.paramType().equalsIgnoreCase("formData")) {
            p = new FormParameter();
        } else if (param.paramType().equalsIgnoreCase("body")) {
            p = new BodyParameter();
        } else if (param.paramType().equalsIgnoreCase("header")) {
            p = new HeaderParameter();
        } else {
            return null;
        }

        return ParameterProcessor.applyAnnotations(swagger, p, apiClass, Arrays.asList(new Annotation[]{param}));
    }

}

