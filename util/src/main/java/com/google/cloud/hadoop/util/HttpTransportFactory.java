/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.hadoop.util;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.client.googleapis.GoogleUtils;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.ApacheHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.flogger.GoogleLogger;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

/**
 * Factory for creating HttpTransport types.
 */
public class HttpTransportFactory {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * Types of HttpTransports the factory can create.
   */
  public enum HttpTransportType {
    APACHE,
    JAVA_NET,
  }

  // Default to javanet implementation.
  public static final HttpTransportType DEFAULT_TRANSPORT_TYPE = HttpTransportType.JAVA_NET;

  /**
   * Create an {@link HttpTransport} based on an type class.
   *
   * @param type The type of HttpTransport to use.
   * @return The resulting HttpTransport.
   * @throws IllegalArgumentException If the proxy address is invalid.
   * @throws IOException If there is an issue connecting to Google's Certification server.
   */
  public static HttpTransport createHttpTransport(HttpTransportType type) throws IOException {
    return createHttpTransport(
        type, /* proxyAddress= */ null, /* proxyUsername= */ null, /* proxyPassword= */ null);
  }

  /**
   * Create an {@link HttpTransport} based on an type class and an optional HTTP proxy.
   *
   * @param type The type of HttpTransport to use.
   * @param proxyAddress The HTTP proxy to use with the transport. Of the form hostname:port. If
   *     empty no proxy will be used.
   * @param proxyUsername The HTTP proxy username to use with the transport. If empty no proxy
   *     username will be used.
   * @param proxyPassword The HTTP proxy password to use with the transport. If empty no proxy
   *     password will be used.
   * @return The resulting HttpTransport.
   * @throws IllegalArgumentException If the proxy address is invalid.
   * @throws IOException If there is an issue connecting to Google's Certification server.
   */
  public static HttpTransport createHttpTransport(
      HttpTransportType type,
      @Nullable String proxyAddress,
      @Nullable RedactedString proxyUsername,
      @Nullable RedactedString proxyPassword)
      throws IOException {
    logger.atFine().log(
        "createHttpTransport(%s, %s, %s, %s)", type, proxyAddress, proxyUsername, proxyPassword);
    checkArgument(
        proxyAddress != null || (proxyUsername == null && proxyPassword == null),
        "if proxyAddress is null then proxyUsername and proxyPassword should be null too");
    checkArgument(
        (proxyUsername == null) == (proxyPassword == null),
        "both proxyUsername and proxyPassword should be null or not null together");
    URI proxyUri = parseProxyAddress(proxyAddress);
    try {
      switch (type) {
        case APACHE:
          Credentials proxyCredentials =
              proxyUsername != null
                  ? new UsernamePasswordCredentials(proxyUsername.value(), proxyPassword.value())
                  : null;
          return createApacheHttpTransport(proxyUri, proxyCredentials);
        case JAVA_NET:
          PasswordAuthentication proxyAuth =
              proxyUsername != null
                  ? new PasswordAuthentication(
                      proxyUsername.value(), proxyPassword.value().toCharArray())
                  : null;
          return createNetHttpTransport(proxyUri, proxyAuth);
        default:
          throw new IllegalArgumentException(
              String.format("Invalid HttpTransport type '%s'", type.name()));
      }
    } catch (GeneralSecurityException e) {
      throw new IOException(e);
    }
  }

  /**
   * Create an {@link ApacheHttpTransport} for calling Google APIs with an optional HTTP proxy.
   *
   * @param proxyUri Optional HTTP proxy URI to use with the transport.
   * @param proxyCredentials Optional HTTP proxy credentials to authenticate with the transport
   *     proxy.
   * @return The resulting HttpTransport.
   * @throws IOException If there is an issue connecting to Google's certification server.
   * @throws GeneralSecurityException If there is a security issue with the keystore.
   */
  public static ApacheHttpTransport createApacheHttpTransport(
      @Nullable URI proxyUri, @Nullable Credentials proxyCredentials)
      throws IOException, GeneralSecurityException {
    checkArgument(
        proxyUri != null || proxyCredentials == null,
        "if proxyUri is null than proxyCredentials should be null too");

    ApacheHttpTransport transport =
        new ApacheHttpTransport.Builder()
            .setSocketFactory(new ApacheSslKeepAliveSocketFactory(GoogleUtils.getCertificateTrustStore()))
            .setProxy(
                proxyUri == null ? null : new HttpHost(proxyUri.getHost(), proxyUri.getPort()))
            .build();

    if (proxyCredentials != null) {
      ((DefaultHttpClient) transport.getHttpClient())
          .getCredentialsProvider()
          .setCredentials(new AuthScope(proxyUri.getHost(), proxyUri.getPort()), proxyCredentials);
    }

    return transport;
  }

  /**
   * Create an {@link NetHttpTransport} for calling Google APIs with an optional HTTP proxy.
   *
   * @param proxyUri Optional HTTP proxy URI to use with the transport.
   * @param proxyAuth Optional HTTP proxy credentials to authenticate with the transport proxy.
   * @return The resulting HttpTransport.
   * @throws IOException If there is an issue connecting to Google's certification server.
   * @throws GeneralSecurityException If there is a security issue with the keystore.
   */
  public static NetHttpTransport createNetHttpTransport(
      @Nullable URI proxyUri, @Nullable PasswordAuthentication proxyAuth)
      throws IOException, GeneralSecurityException {
    checkArgument(
        proxyUri != null || proxyAuth == null,
        "if proxyUri is null than proxyAuth should be null too");

    if (proxyAuth != null) {
      // Enable "Basic" authentication on JDK 8+
      System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
      Authenticator.setDefault(
          new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
              if (getRequestorType() == RequestorType.PROXY
                  && getRequestingHost().equalsIgnoreCase(proxyUri.getHost())
                  && getRequestingPort() == proxyUri.getPort()) {
                return proxyAuth;
              }
              return null;
            }
          });
    }

    NetHttpTransport.Builder builder =
        prepareNetHttpTransportBuilder(GoogleUtils.getCertificateTrustStore(), proxyUri);
    return builder.build();
  }

  @VisibleForTesting
  static NetHttpTransport.Builder prepareNetHttpTransportBuilder(
      KeyStore keyStore, @Nullable URI proxyUri) throws GeneralSecurityException {
    NetHttpTransport.Builder builder = new NetHttpTransport.Builder().trustCertificates(keyStore);
    SSLSocketFactory socketFactory = builder.getSslSocketFactory();
    if (socketFactory == null) {
      socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
    }
    return builder
        .setSslSocketFactory(new JavaxSslKeepAliveSocketFactory(socketFactory))
        .setProxy(
            proxyUri == null
                ? null
                : new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort())));
  }

  /**
   * Parse an HTTP proxy from a String address.
   * @param proxyAddress The address of the proxy of the form (https?://)HOST:PORT.
   * @return The URI of the proxy.
   * @throws IllegalArgumentException If the address is invalid.
   */
  @VisibleForTesting
  static URI parseProxyAddress(@Nullable String proxyAddress) {
    if (Strings.isNullOrEmpty(proxyAddress)) {
      return null;
    }
    String uriString = (proxyAddress.contains("//") ? "" : "//") + proxyAddress;
    try {
      URI uri = new URI(uriString);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      int port = uri.getPort();
      checkArgument(
          Strings.isNullOrEmpty(scheme) || scheme.matches("https?"),
          "HTTP proxy address '%s' has invalid scheme '%s'.", proxyAddress, scheme);
      checkArgument(!Strings.isNullOrEmpty(host), "Proxy address '%s' has no host.", proxyAddress);
      checkArgument(port != -1, "Proxy address '%s' has no port.", proxyAddress);
      checkArgument(
          uri.equals(new URI(scheme, null, host, port, null, null, null)),
          "Invalid proxy address '%s'.", proxyAddress);
      return uri;
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          String.format("Invalid proxy address '%s'.", proxyAddress), e);
    }
  }

  @VisibleForTesting
  static class ApacheSslKeepAliveSocketFactory extends org.apache.http.conn.ssl.SSLSocketFactory {

    public ApacheSslKeepAliveSocketFactory(KeyStore keyStore) throws GeneralSecurityException {
      super(keyStore);
    }

    @Override
    public Socket createSocket() throws IOException {
      return setKeepAlive(super.createSocket());
    }

    @Override
    public Socket createSocket(HttpParams params) throws IOException {
      return setKeepAlive(super.createSocket(params));
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
        throws IOException, UnknownHostException {
      return setKeepAlive(super.createSocket(socket, host, port, autoClose));
    }

    @Override
    public Socket createSocket(HttpContext context) throws IOException {
      return setKeepAlive(super.createSocket(context));
    }
  }

  /** Wrapper class to have socketKeepAlive property while creating the socket */
  @VisibleForTesting
  static class JavaxSslKeepAliveSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory wrappedSockedFactory;

    public JavaxSslKeepAliveSocketFactory(SSLSocketFactory wrappedSocketFactory) {
      this.wrappedSockedFactory = wrappedSocketFactory;
    }

    @Override
    public String[] getDefaultCipherSuites() {
      return wrappedSockedFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
      return wrappedSockedFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
      return setKeepAlive(wrappedSockedFactory.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, InputStream consumed, boolean autoClose)
        throws IOException {
      return setKeepAlive(wrappedSockedFactory.createSocket(s, consumed, autoClose));
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
        throws IOException {
      return setKeepAlive(wrappedSockedFactory.createSocket(s, host, port, autoClose));
    }

    public Socket createSocket(String host, int port) throws IOException {
      return setKeepAlive(wrappedSockedFactory.createSocket(host, port));
    }

    public Socket createSocket(InetAddress address, int port) throws IOException {
      return setKeepAlive(wrappedSockedFactory.createSocket(address, port));
    }

    public Socket createSocket(String host, int port, InetAddress clientAddress, int clientPort)
        throws IOException {
      return setKeepAlive(
          wrappedSockedFactory.createSocket(host, port, clientAddress, clientPort));
    }

    public Socket createSocket(
        InetAddress address, int port, InetAddress clientAddress, int clientPort)
        throws IOException {
      return setKeepAlive(
          wrappedSockedFactory.createSocket(address, port, clientAddress, clientPort));
    }

  }

  private static Socket setKeepAlive(Socket socket) throws SocketException {
    socket.setKeepAlive(true);
    return socket;
  }
}
