package com.ibm.streamsx.elasticsearch.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import org.apache.http.HttpHeaders;
import org.apache.http.NoHttpResponseException;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;
import org.apache.log4j.Logger;


import io.searchbox.action.AbstractAction;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.client.config.exception.CouldNotConnectException;
import io.searchbox.core.Bulk;
import io.searchbox.core.Bulk.Builder;
import io.searchbox.core.BulkResult;
import io.searchbox.core.BulkResult.BulkResultItem;
import io.searchbox.core.Index;

/**
 * implementation for the JEST client library
 *
 */
public class JESTClient implements Client {
	
	// external properties
	private Logger logger = null;
	private Configuration cfg = null;
	private ClientMetrics clientMetrics = null;
	
	// internal properties
	private JestClient client = null;
	private Builder bulkBuilder = null;
	private int bulkSize = 0;
	private final static String defaultType = "_doc";
	private int numberOfNodes = 1;
	
	// http basic authentication 
	private boolean useBasicAuth = false;
	private String authHeader = null;

	public JESTClient(Configuration config, ClientMetrics clientMetrics) {
		super();
		this.cfg = config;
		this.clientMetrics = clientMetrics;
	}

	@Override
	public void setConfiguration(Configuration config) {
		cfg = config;
		logger.trace("Configuration set: " + config.toString() );
	}
	
	@Override
	public ClientMetrics getClientMetrics() {
		return clientMetrics;
	}	
	
	@Override
	public void setLogger(Logger logger) {
		this.logger = logger;
	}

	// validate the configuration, if something is wrong log error and return false
	// if false is returned, the caller should assume this as an unrecoverable error and stop execution
	@Override
	public boolean validateConfiguration() {

		// verify at least one node is specified
		if (cfg.getNodeList().size() < 1) {
			logger.error("Config error: no valid node specified");
			return false;
		}
		
		// if useranme is given , password must also be specified
		if (cfg.getUserName() != null && cfg.getPassword() == null) {
			logger.error("Config error: userName for HTTP basic authentication is specified, but no password is set.");
			return false;
		}
		return true;
	}

	// initialize the client, if something goes wrong log error and return false
	// if false is returned the caller should assume this as an unrecoverable error and stop execution
	@Override
	public boolean init() throws Exception {

	    JestClientFactory factory = new JestClientFactory();
	    client = null;
	    numberOfNodes = cfg.getNodeList().size();
	       
	    // create basic authentication header if needed
        if (cfg.getUserName() != null) {
        	String credentials = cfg.getUserName() + ":" + cfg.getPassword();
        	byte[] encodedCredentials = Base64.getEncoder().encode(credentials.getBytes(StandardCharsets.ISO_8859_1));
        	authHeader = "Basic " + new String(encodedCredentials);
        	useBasicAuth = true;
        }
	    
		// use ssl 
		if (cfg.isSslEnabled()) {

			// in case we are running on an IBM Java, where TLSv1.2 is not enabled per default, set this property
			// the IBM Cloud Elasticsearch compose service needs TLS1.2, otherwise it will close connection immediately
			System.setProperty("com.ibm.jsse2.overrideDefaultTLS","true");
			
			// enable debug, if needed
			if (cfg.isSslDebug()) {
				System.setProperty("javax.net.debug","true");
			}
			
			SSLContext sslContext = null;
			SSLConnectionSocketFactory sslSocketFactory = null;
			
			// trust all certificates , use use supplied truststore, or use java defaults
			if (cfg.isSslTrustAllCertificates()) {
				try {
					sslContext = new SSLContextBuilder().loadTrustMaterial(new TrustStrategy() {
						@Override
						public boolean isTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
							// trust all certificates
							return true;
						}
					}).build();
				} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
					logger.error("Init error: cannot build SSLContext with trustAllCertificates option, see stack trace for details");
					e.printStackTrace();
					return false;
				}
			} else if (cfg.getSslTrustStore() != null) {
				File trustFile = new File(cfg.getSslTrustStore());
				if (cfg.getSslTrustStorePassword() != null) {
					try {
						sslContext = new SSLContextBuilder().loadTrustMaterial(trustFile,cfg.getSslTrustStorePassword().toCharArray()).build();
					} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException e) {
						logger.error("Init error: cannot build SSLContext with truststore and truststore password, see stack trace for details");
						e.printStackTrace();
						return false;
					}
				} else {
					try {
						sslContext = new SSLContextBuilder().loadTrustMaterial(trustFile).build();
					} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException e) {
						logger.error("Init error: cannot build SSLContext with truststore (no passwd), see stack trace for details");
						e.printStackTrace();
						return false;
					}
				}
			} else {
				try {
					sslContext = SSLContext.getDefault();
				} catch (NoSuchAlgorithmException e) {
					logger.error("Init error: cannot build default SSLContext, see stack trace for details");
					e.printStackTrace();
					return false;
				}
			}
			
			// verify hostname or not
			if (!cfg.isSslVerifyHostname()) {
				HostnameVerifier hostnameVerifier = NoopHostnameVerifier.INSTANCE;
				sslSocketFactory = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);
			} else {
				sslSocketFactory = new SSLConnectionSocketFactory(sslContext);
			}

       	  	factory.setHttpClientConfig(new HttpClientConfig.Builder(cfg.getNodeList())
        	  		.sslSocketFactory(sslSocketFactory)
        	  		.multiThreaded(false)
       	  			.readTimeout(cfg.getReadTimeout())
       	  			.connTimeout(cfg.getConnectionTimeout())
       	  			.maxConnectionIdleTime(cfg.getMaxConnectionIdleTime(), TimeUnit.MILLISECONDS)
        	  		.build());
		
	    // use HTTP only 
		} else {
       	  	factory.setHttpClientConfig(new HttpClientConfig.Builder(cfg.getNodeList())
       	  			.multiThreaded(false)
       	  			.readTimeout(cfg.getReadTimeout())
       	  			.connTimeout(cfg.getConnectionTimeout())
       	  			.maxConnectionIdleTime(cfg.getMaxConnectionIdleTime(), TimeUnit.MILLISECONDS)
        	  		.build());
		}
        
        client = factory.getObject();
        clientMetrics.setIsConnected(true);

        if (null == client) {
        	logger.error("Init error: unknown problem, client is 'null'");
        	return false;
        }
        return true;
	}

	@Override
	public void close() {
		try {
			client.close();
		} catch (IOException e) {
			logger.error("Exception during closing the JEST client, message: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Override
	public void bulkIndexAddDocument(String document, String indexToInsert, String typeToInsert, String idToInsert) {
		// reset bulk builder
		if (null == bulkBuilder) {
			bulkBuilder = new Bulk.Builder().defaultIndex(indexToInsert).defaultType(typeToInsert);
		}

		// set a default type , as types will be removed in ES7
		// with ES6 only  one type per index is allowed. The default should be named _doc
		String docType = defaultType;
		if (null != typeToInsert) {
			docType = typeToInsert;
		}
		
		if (idToInsert != null) {
			bulkBuilder.addAction(new Index.Builder(document).index(indexToInsert).type(docType).id(idToInsert).build());
		} else {
			bulkBuilder.addAction(new Index.Builder(document).index(indexToInsert).type(docType).build());
		}
		
		bulkSize++;
	}

	// Method to send any request to ES, it handles connection retries and exceptions from the jest/apache http clients
	private <T extends AbstractAction<E>, E extends JestResult> E executeRequest(T request) {
		
		int attempts = 0;
		int nodesFailed = 0;
		int reconnects = 0;
		boolean retry = true;
		E response = null;
		boolean gotResponse = false;
		
		while (retry) {
			try {
				response = client.execute(request);
				gotResponse = true;
			} catch (NoHttpResponseException e) {
				logger.error("HTTP error. Cannot send request to server. Exception : " + e.getMessage());
			} catch (CouldNotConnectException e) {
				logger.error("Connect error. Cannot send request to server. Exception : " + e.getMessage());
			} catch (IOException e) {
				logger.error("IO error. Cannot send request to server. Exception : " + e.getMessage());
				e.printStackTrace();
			}
			attempts++;
			
			if (gotResponse) {
				retry = false;
				clientMetrics.setIsConnected(true);
			} else {
				clientMetrics.setIsConnected(false);
				// if we have nodes left in the cluster, we try to immediately send the request to the next node
				if (attempts < numberOfNodes) {
					retry = true;
					logger.error("Attempt: " + Integer.toString(attempts) + " failed, retrying without wait interval ...");
				} else
				// if all nodes failed, we try to reconnect with a wait interval 
				{
					reconnects++;
					clientMetrics.incrementReconnectionCount();
					if (reconnects <= cfg.getReconnectionPolicyCount()) {
						logger.error("Attempt: " + Integer.toString(attempts) + " failed, retrying with wait, reconnect: " + Integer.toString(reconnects) + " ...");
						retry = true;
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							logger.warn("Thread.sleep interrupted");
						}
					} else {
						retry = false;
						logger.error("Attempt: " + Integer.toString(attempts) + " failed, giving up");
					}
				}
			}
		}
		
		if (logger.isDebugEnabled()) {
			nodesFailed = Math.min((attempts-1),numberOfNodes);
			logger.debug("Nodes failed: " + Integer.toString(nodesFailed));
		}
		return response;
	}
	
	@Override
	public void bulkIndexSend() {

		if (null == bulkBuilder) {
			logger.debug("the bulk is empty, nothing to send");
			return;
		}
		
		if (useBasicAuth) {
			bulkBuilder.setHeader(HttpHeaders.AUTHORIZATION, authHeader);
		}
		
		Bulk bulk = bulkBuilder.build();
		BulkResult result = null;

		// execute the request
		result = executeRequest(bulk);

		// evaluate the result of the bulk index operation 
		int failedInserts = 0;
		if (null == result) {
			logger.error("Bulk send failed, response object is null. Bulk size = " + Integer.toString(bulkSize));
			clientMetrics.incrementTotalFailedRequests();
		} else {
			if (result.isSucceeded()) {
				clientMetrics.incrementNumInserts();
				if (logger.isDebugEnabled()) {
					logger.debug("Bulk send successfully, size = " + Integer.toString(bulkSize));
				}
			} else {
				if (result.getErrorMessage() != null) {
					logger.error("Bulk send failed. bulk size = " + Integer.toString(bulkSize));
					logger.error("Error: " + result.getErrorMessage());
				    if (null != result.getItems()) {
						for (BulkResultItem item : result.getItems()) {
							if (item.error != null) {
								failedInserts++;
								logger.error("Bulk item indexing failed. " + item.error);
							}
						}
						logger.error("Total items = " + Integer.toString(bulkSize) + ", failed = " + Integer.toString(failedInserts));
				    }
				}
			}
		}

		bulkBuilder = null;
		bulkSize = 0;
		
	}

	
	public void reset() {
		bulkBuilder = null;
		bulkSize = 0;
	}	

}
