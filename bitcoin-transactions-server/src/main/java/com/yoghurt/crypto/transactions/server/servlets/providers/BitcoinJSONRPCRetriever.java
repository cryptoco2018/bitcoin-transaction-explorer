package com.yoghurt.crypto.transactions.server.servlets.providers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.SystemDefaultCredentialsProvider;

import com.yoghurt.crypto.transactions.server.domain.BitcoinCoreNodeConfig;
import com.yoghurt.crypto.transactions.server.util.HttpClientProxy;
import com.yoghurt.crypto.transactions.server.util.json.core.JSONRPCEncoder;
import com.yoghurt.crypto.transactions.server.util.json.core.JSONRPCParser;
import com.yoghurt.crypto.transactions.shared.domain.AddressInformation;
import com.yoghurt.crypto.transactions.shared.domain.ApplicationException;
import com.yoghurt.crypto.transactions.shared.domain.BlockInformation;
import com.yoghurt.crypto.transactions.shared.domain.OutpointInformation;
import com.yoghurt.crypto.transactions.shared.domain.TransactionInformation;

public class BitcoinJSONRPCRetriever implements BlockchainRetrievalService {
  private static final String JSON_RPC_REALM = "jsonrpc";
  private static final String AUTH_SCHEME = AuthSchemes.BASIC;

  private static final String URI_FORMAT = "http://%s:%s@%s:%s";

  /**
   * The JSON-RPC interface doesn't return the genesis coinbase transaction, so
   * it's been hard-coded here.
   */
  private static final String GENESIS_COINBASE_TXID = "4A5E1E4BAAB89F3A32518A88C31BC87F618F76673E2CC77AB2127B7AFDEDA33B";
  private static final String GENESIS_COINBASE_RAW = "01000000010000000000000000000000000000000000000000000000000000000000000000FFFFFFFF4D04FFFF001D0104455468652054696D65732030332F4A616E2F32303039204368616E63656C6C6F72206F6E206272696E6B206F66207365636F6E64206261696C6F757420666F722062616E6B73FFFFFFFF0100F2052A01000000434104678AFDB0FE5548271967F1A67130B7105CD6A828E03909A67962E0EA1F61DEB649F6BC3F4CEF38C4F35504E51EC112DE5C384DF7BA0B8D578A4C702B6BF11D5FAC00000000";

  protected final String uri;

  private final HttpClientContext localContext;
  private final CredentialsProvider credentialsProvider = new SystemDefaultCredentialsProvider();

  public BitcoinJSONRPCRetriever(final BitcoinCoreNodeConfig config) {
    this(config.getHost(), Integer.parseInt(config.getPort()), config.getRpcUser(), config.getRpcPass());
  }

  protected BitcoinJSONRPCRetriever(final String host, final int port, final String rpcUser, final String rpcPassword) {
    uri = String.format(URI_FORMAT, rpcUser, rpcPassword, host, port);
    credentialsProvider.setCredentials(new AuthScope(host, port, JSON_RPC_REALM, AUTH_SCHEME), new UsernamePasswordCredentials(rpcUser, rpcPassword));

    final AuthCache authCache = new BasicAuthCache();
    authCache.put(new HttpHost(host, port), new BasicScheme());

    localContext = HttpClientContext.create();
    localContext.setAuthCache(authCache);
  }

  @Override
  public String getLatestBlockHash() throws ApplicationException {
    try {
      return doSimpleJSONRPCMethod("getbestblockhash");
    } catch (IOException | HttpException e) {
      e.printStackTrace();
      throw new ApplicationException(e.getMessage());
    }
  }

  @Override
  public TransactionInformation getTransactionInformation(final String txid) throws ApplicationException {
    if (GENESIS_COINBASE_TXID.equalsIgnoreCase(txid)) {
      final TransactionInformation ti = new TransactionInformation();

      ti.setRawHex(GENESIS_COINBASE_RAW);

      return ti;
    }

    try (CloseableHttpClient client = getAuthenticatedHttpClientProxy();
        InputStream jsonData = doComplexJSONRPCMethod(client, "getrawtransaction", txid, 1).getContent()) {

      return JSONRPCParser.getTransactionInformation(jsonData);
    } catch (IOException | HttpException e) {
      e.printStackTrace();
      throw new ApplicationException(e.getMessage());
    }
  }

  @Override
  public BlockInformation getBlockInformationFromHeight(final int height) throws ApplicationException {
    return getBlockInformationFromHash(getBlockHashFromHeight(height));
  }

  @Override
  public AddressInformation getAddressInformation(final String address) throws ApplicationException {
    try (final CloseableHttpClient client = getAuthenticatedHttpClientProxy();
        final InputStream jsonData = doComplexJSONRPCMethod(client, "searchrawtransactions", address).getContent()) {

      final AddressInformation addressInformation = JSONRPCParser.getAddressInformation(address, jsonData);

      for (final OutpointInformation outpoint : addressInformation.getOutpoints()) {
        final String txid = outpoint.getReferenceTransaction();
        try (final InputStream utxoJsonData = doComplexJSONRPCMethod(client, "gettxout", txid, outpoint.getIndex()).getContent()) {
          outpoint.setSpent(JSONRPCParser.isNullResult(utxoJsonData));
        }
      }

      return addressInformation;
    } catch (IOException | HttpException | DecoderException e) {
      e.printStackTrace();
      throw new ApplicationException(e.getMessage());
    }
  }

  private String getBlockHashFromHeight(final int height) throws ApplicationException {
    try {
      return doSimpleJSONRPCMethod("getblockhash", height);
    } catch (final IOException | HttpException e) {
      e.printStackTrace();
      throw new ApplicationException(e.getMessage());
    }
  }

  @Override
  public BlockInformation getBlockInformationLast() throws ApplicationException {
    return getBlockInformationFromHash(getLatestBlockHash());
  }

  @Override
  public ArrayList<String> getTransactionList(final int height) throws ApplicationException {
    try (CloseableHttpClient client = getAuthenticatedHttpClientProxy();
        InputStream jsonData = doComplexJSONRPCMethod(client, "getblock", getBlockHashFromHeight(height)).getContent()) {

      return JSONRPCParser.getTransactionList(jsonData);
    } catch (IOException | HttpException e) {
      e.printStackTrace();
      System.out.println("What up.");
      throw new ApplicationException(e.getMessage());
    }
  }

  @Override
  public BlockInformation getBlockInformationFromHash(final String identifier) throws ApplicationException {
    try (CloseableHttpClient client = getAuthenticatedHttpClientProxy();
        InputStream jsonData = doComplexJSONRPCMethod(client, "getblock", identifier).getContent()) {

      final BlockInformation blockInformation = JSONRPCParser.getBlockInformation(jsonData);

      // Extract the coinbase tx id (TODO: Refactor, shouldn't be using a mock
      // object like this)
      final String coinbaseTxid = blockInformation.getCoinbaseInformation().getRawHex();

      // Retrieve raw coinbase tx and its blockchain information
      final TransactionInformation ti = getTransactionInformation(coinbaseTxid);

      // Stick it in the block info
      blockInformation.setCoinbaseInformation(ti);

      // Return the result
      return blockInformation;
    } catch (IOException | HttpException | DecoderException e) {
      e.printStackTrace();
      throw new ApplicationException(e.getMessage());
    }
  }

  protected String doSimpleJSONRPCMethod(final String method, final Object... params) throws IOException, HttpException {
    try (CloseableHttpClient client = getAuthenticatedHttpClientProxy();
        InputStream stream = doComplexJSONRPCMethod(client, method, params).getContent()) {
      return JSONRPCParser.getResultString(stream);
    }
  }

  protected HttpEntity doComplexJSONRPCMethod(final CloseableHttpClient client, final String method, final Object... params)
      throws IOException, IllegalStateException, ParseException, HttpException {
    final String payload = JSONRPCEncoder.getRequestString(method, params);

    System.out.println(uri);

    // Temporary
    System.out.println("> " + payload);

    return HttpClientProxy.postRemoteContent(client, uri, payload);
  }

  protected CloseableHttpClient getAuthenticatedHttpClientProxy() {
    return HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider).build();
  }

  protected CloseableHttpClient getHttpClientProxy() {
    return HttpClients.createDefault();
  }
}
