package io.kroki.server.service;

import io.kroki.server.decode.DiagramSource;
import io.kroki.server.decode.SourceDecoder;
import io.kroki.server.error.DecodeException;
import io.kroki.server.format.FileFormat;
import io.kroki.server.response.Caching;
import io.kroki.server.response.DiagramResponse;
import io.kroki.server.security.SafeMode;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import net.sourceforge.plantuml.version.Version;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class C4Plantuml implements DiagramService {

  private static final List<FileFormat> SUPPORTED_FORMATS = Arrays.asList(FileFormat.PNG, FileFormat.SVG, FileFormat.JPEG, FileFormat.BASE64);

  private final SafeMode safeMode;
  private static final String c4 = read("c4.puml");
  // context includes c4
  private static final String c4Context= c4 + read("c4_context.puml");
  // container includes context
  private static final String c4Container= c4Context + read("c4_container.puml");
  // component includes container
  private static final String c4Component = c4Container + read("c4_component.puml");

  private final SourceDecoder sourceDecoder;
  private final DiagramResponse diagramResponse;

  public C4Plantuml(JsonObject config) {
    this.safeMode = SafeMode.get(config.getString("KROKI_SAFE_MODE", "secure"), SafeMode.SECURE);
    this.sourceDecoder = new SourceDecoder() {
      @Override
      public String decode(String encoded) throws DecodeException {
        return DiagramSource.plantumlDecode(encoded);
      }
    };
    this.diagramResponse = new DiagramResponse(new Caching(Version.etag()));
  }

  @Override
  public List<FileFormat> getSupportedFormats() {
    return SUPPORTED_FORMATS;
  }

  @Override
  public SourceDecoder getSourceDecoder() {
    return sourceDecoder;
  }

  @Override
  public void convert(RoutingContext routingContext, String sourceDecoded, String serviceName, FileFormat fileFormat) {
    HttpServerResponse response = routingContext.response();
    String source;
    try {
      source = sanitize(sourceDecoded, safeMode);
      source = Plantuml.withDelimiter(source);
    } catch (IOException e) {
      routingContext.fail(e);
      return;
    }
    byte[] data = Plantuml.convert(source, fileFormat);
    diagramResponse.end(response, sourceDecoded, fileFormat, data);
  }

  static String sanitize(String input, SafeMode safeMode) throws IOException {
    try (BufferedReader reader = new BufferedReader(new StringReader(input))) {
      StringBuilder sb = new StringBuilder();
      String line = reader.readLine();
      while (line != null) {
        processInclude(line, sb, safeMode);
        line = reader.readLine();
      }
      return sb.toString();
    }
  }

  private static void processInclude(String line, StringBuilder sb, SafeMode safeMode) {
    Matcher matcher = Plantuml.INCLUDE_RX.matcher(line);
    if (matcher.matches()) {
      String path = matcher.group("path");
      if (path.toLowerCase().contains("c4.puml")) {
        sb.append(c4).append("\n");
      } else if (path.toLowerCase().contains("c4_component.puml")) {
        sb.append(c4Component).append("\n");
      } else if (path.toLowerCase().contains("c4_container.puml")) {
        sb.append(c4Container).append("\n");
      } else if (path.toLowerCase().contains("c4_context.puml")) {
        sb.append(c4Context).append("\n");
      } else if (safeMode == SafeMode.UNSAFE) {
        sb.append(line).append("\n");
      }
    } else {
      sb.append(line).append("\n");
    }
  }

  private static String read(String resource){
    InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
    try {
      if (input == null) {
        throw new IOException("Unable to get resource: " + resource);
      }
      try (BufferedReader buffer = new BufferedReader(new InputStreamReader(input))) {
        return buffer.lines().collect(Collectors.joining("\n"));
      }
    } catch (IOException e) {
      throw new RuntimeException("Unable to initialize the C4 PlantUML service", e);
    }
  }
}
