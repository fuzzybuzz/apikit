/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.amf.impl;

import amf.client.AMF;
import amf.client.environment.DefaultEnvironment;
import amf.client.environment.Environment;
import amf.client.model.document.BaseUnit;
import amf.client.model.document.Document;
import amf.client.model.domain.WebApi;
import amf.client.parse.Parser;
import amf.client.parse.RamlParser;
import amf.client.render.AmfGraphRenderer;
import amf.client.render.Oas20Renderer;
import amf.client.render.Raml08Renderer;
import amf.client.render.Raml10Renderer;
import amf.client.render.Renderer;
import amf.client.validate.ValidationReport;
import amf.client.validate.ValidationResult;
import amf.core.remote.Vendor;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.mule.amf.impl.loader.ApiSyncResourceLoader;
import org.mule.amf.impl.loader.ExchangeDependencyResourceLoader;
import org.mule.amf.impl.model.AmfImpl;
import org.mule.amf.impl.parser.rule.ValidationResultImpl;
import org.mule.apikit.common.APISyncUtils;
import org.mule.raml.interfaces.ParserType;
import org.mule.raml.interfaces.ParserWrapper;
import org.mule.raml.interfaces.injector.IRamlUpdater;
import org.mule.raml.interfaces.model.ApiRef;
import org.mule.raml.interfaces.model.ApiVendor;
import org.mule.raml.interfaces.model.IRaml;
import org.mule.raml.interfaces.parser.rule.DefaultValidationReport;
import org.mule.raml.interfaces.parser.rule.IValidationReport;
import org.mule.raml.interfaces.parser.rule.IValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import static amf.ProfileNames.AMF;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.mule.amf.impl.DocumentParser.getParserForApi;
import static org.mule.amf.impl.DocumentParser.getWebApi;
import static org.mule.raml.interfaces.common.RamlUtils.replaceBaseUri;

public class ParserWrapperAmf implements ParserWrapper {

  private static final Logger logger = LoggerFactory.getLogger(ParserWrapperAmf.class);

  private final Parser parser;
  private final Document document;
  private final WebApi webApi;
  private final ApiVendor apiVendor;
  private final List<ApiRef> references;

  private static final String VENDOR_RAML_08 = "RAML 0.8";
  private static final String VENDOR_RAML_10 = "RAML 1.0";
  private static final String VENDOR_OAS_20 = "OAS 2.0";

  private ParserWrapperAmf(final URI uri, Environment environment, boolean validate) {
    parser = getParserForApi(uri, environment);
    document = DocumentParser.parseFile(parser, uri, validate);
    references = getReferences(document.references());
    webApi = getWebApi(parser, uri);
    final Option<Vendor> vendor = webApi.sourceVendor();
    apiVendor = vendor.isDefined() ? getApiVendor(vendor.get()) : ApiVendor.RAML_10;
  }

  // TODO Improve this !!! We are working only with RAML Parser on APISync
  private ParserWrapperAmf(final String apiPath, Environment environment, boolean validate) {
    parser = new RamlParser(environment);
    document = DocumentParser.parseFile(parser, apiPath, validate);
    references = getReferences(document.references());
    webApi = getWebApi(document);
    final Option<Vendor> vendor = webApi.sourceVendor();
    apiVendor = vendor.isDefined() ? getApiVendor(vendor.get()) : ApiVendor.RAML_10;
  }

  private List<ApiRef> getReferences(final List<BaseUnit> references) {

    final List<ApiRef> result = new ArrayList<>();
    appendReferences(references, new HashSet<>(), result);
    return result;
  }

  private void appendReferences(final List<BaseUnit> references, final Set<String> alreadyAdded, final List<ApiRef> result) {

    for (final BaseUnit reference : references) {
      final String id = reference.id();
      if (!alreadyAdded.contains(id)) {
        final String location = reference.location();
        result.add(ApiRef.create(location));
        alreadyAdded.add(id);
        appendReferences(reference.references(), alreadyAdded, result);
      }
    }
  }

  private static ApiVendor getApiVendor(final Vendor vendor) {

    final ApiVendor result;

    final String name = vendor.name();
    switch (name) {
      case VENDOR_OAS_20:
        result = ApiVendor.OAS_20;
        break;
      case VENDOR_RAML_08:
        result = ApiVendor.RAML_08;
        break;
      default:
        result = ApiVendor.RAML_10;
        break;
    }
    return result;
  }

  public static ParserWrapperAmf create(URI apiUri, boolean validate) throws Exception {
    return create(apiUri, buildEnvironment(apiUri), validate);
  }

  public static ParserWrapperAmf create(String rootRaml, URI apiUri, boolean validate) throws Exception {
    if (APISyncUtils.isSyncProtocol(rootRaml)) {
      return create(rootRaml, buildApiSyncEnvironment(rootRaml), validate);
    } else {
      return create(apiUri, buildEnvironment(apiUri), validate);
    }
  }

  public static ParserWrapperAmf create(String rootRaml, Environment environment, boolean validate) throws Exception {
    AMF.init().get();
    return new ParserWrapperAmf(rootRaml, environment, validate);
  }

  public static ParserWrapperAmf create(URI apiUri, Environment environment, boolean validate) throws Exception {
    AMF.init().get();
    return new ParserWrapperAmf(apiUri, environment, validate);
  }

  private static Environment buildEnvironment(URI uri) {
    Environment environment = DefaultEnvironment.apply();
    if (uri.getScheme() != null && uri.getScheme().startsWith("file")) {
      final File file = new File(uri);
      final String rootDir = file.isDirectory() ? file.getPath() : file.getParent();
      environment = environment.add(new ExchangeDependencyResourceLoader(rootDir));
    }
    return environment;
  }

  private static Environment buildApiSyncEnvironment(String rootRaml) {
    Environment environment = DefaultEnvironment.apply();
    if (rootRaml != null) {
      environment = environment.add(new ApiSyncResourceLoader(rootRaml));
    }
    return environment;
  }

  @Override
  public ApiVendor getApiVendor() {
    return apiVendor;
  }

  @Override
  public ParserType getParserType() {
    return ParserType.AMF;
  }

  @Override
  public void validate() {

    final ValidationReport validationReport;
    try {
      validationReport = parser.reportValidation(AMF()).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException("Unexpected error parsing API: " + e.getMessage(), e);
    }

    if (!validationReport.conforms()) {
      final String errorMessge = "Invalid API descriptor -- errors found: " +
          validationReport.results().size() +
          "\n\n" +
          validationReport.results().stream().map(ValidationResult::message).collect(joining("\n"));

      throw new RuntimeException(errorMessge);
    }
  }

  @Override
  public IValidationReport validationReport() {
    final ValidationReport validationReport;
    try {
      validationReport = parser.reportValidation(AMF()).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException("Unexpected error parsing API: " + e.getMessage(), e);
    }

    List<IValidationResult> results;
    if (!validationReport.conforms())
      results = validationReport.results().stream().map(ValidationResultImpl::new).collect(toList());
    else
      results = emptyList();

    return new DefaultValidationReport(results);
  }

  @Override
  public IRaml build() {
    return new AmfImpl(webApi, references);
  }

  @Override
  public String dump(final String ramlContent, final IRaml api, final String oldBaseUri, final String newBaseUri) {
    return replaceBaseUri(ramlContent, newBaseUri);
  }

  @Override

  public String dump(final IRaml api, final String newBaseUri) {
    String dump = dumpRaml(api);
    if (newBaseUri != null) {
      dump = replaceBaseUri(dump, newBaseUri);
    }
    return dump;
  }

  @Override
  public IRamlUpdater getRamlUpdater(final IRaml api) {
    throw new UnsupportedOperationException();
  }

  private String dumpRaml(final IRaml api) {
    return renderApi();
  }

  @Override
  public void updateBaseUri(IRaml api, String baseUri) {}

  public String getAmfModel() {
    try {
      return new AmfGraphRenderer().generateString(document).get();
    } catch (InterruptedException | ExecutionException e) {
      return e.getMessage();
    }
  }

  private String renderApi() {

    final Renderer renderer;
    switch (apiVendor) {
      case RAML_08:
        renderer = new Raml08Renderer();
        break;
      case OAS_20:
        renderer = new Oas20Renderer();
        break;
      default:
        renderer = new Raml10Renderer();
        break;
    }

    try {
      return renderer.generateString(document).get();
    } catch (final InterruptedException | ExecutionException e) {
      e.printStackTrace();
      return "";
    }
  }

  public InputStream fetchResource(String resource) {
    //TODO: Implement!!!
    return null;
  }
}