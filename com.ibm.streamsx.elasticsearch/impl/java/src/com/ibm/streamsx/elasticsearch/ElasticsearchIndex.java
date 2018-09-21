//
// ****************************************************************************
// * Copyright (C) 2018, International Business Machines Corporation          *
// * All rights reserved.                                                     *
// ****************************************************************************
//

package com.ibm.streamsx.elasticsearch;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;

import org.apache.http.NoHttpResponseException;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.log4j.Logger;

import com.ibm.json.java.JSONObject;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.TupleAttribute;
import com.ibm.streams.operator.Type;
import com.ibm.streams.operator.metrics.Metric;
import com.ibm.streams.operator.model.CustomMetric;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streamsx.elasticsearch.client.Client;
import com.ibm.streamsx.elasticsearch.client.Configuration;
import com.ibm.streamsx.elasticsearch.client.JESTClient;
import com.ibm.streamsx.elasticsearch.internal.SizeMapping;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestResult;
import io.searchbox.core.Bulk;
import io.searchbox.core.Bulk.Builder;
import io.searchbox.core.BulkResult;
import io.searchbox.core.BulkResult.BulkResultItem;
import io.searchbox.core.Index;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;
import io.searchbox.core.search.aggregation.ExtendedStatsAggregation;
import io.searchbox.indices.CreateIndex;
import io.searchbox.indices.IndicesExists;

@PrimitiveOperator(name="ElasticsearchIndex", namespace="com.ibm.streamsx.elasticsearch", description=ElasticsearchIndex.operatorDescription)
@InputPorts({@InputPortSet(
		id="0",
		description=ElasticsearchIndex.iport0Description,
		cardinality=1,
		optional=false,
		windowingMode=WindowMode.NonWindowed,
		windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious)
})
public class ElasticsearchIndex extends AbstractElasticsearchOperator
{
	// operator parameter members --------------------------------------------------------------------------- 
	
	private int bulkSize = 1;
	private boolean sizeMetricsEnabled = false;
	
	private String indexName;
	private TupleAttribute<Tuple, String> indexNameAttribute;

	private String typeName;
	private TupleAttribute<Tuple, String> typeNameAttribute;

	private String idName;
	private TupleAttribute<Tuple, String> idNameAttribute;
	
	private boolean storeTimestamps = false;	
	private String timestampName = "timestamp";
	private TupleAttribute<Tuple, Long> timestampValueAttribute;
	
	// internal members -------------------------------------------------------------------------------------
	
	/**
	 * Logger for tracing.
	 */
	private static Logger logger = Logger.getLogger(ElasticsearchIndex.class.getName());
	
	/**
	 * Property names for size metrics in Elasticsearch.
	 */
	private static String SIZE_METRICS_PROPERTY = "_size_metrics";

	/**
	 * Elasticsearch Jest API.
	 */
	private Client client;
	private JestClient rawClient;
	private Builder bulkBuilder;
	private int currentBulkSize = 0;
	private Configuration config = null;
	
	/**
	 * Metrics
	 */
	private Metric isConnected;
	private Metric totalFailedRequests;
	private Metric numInserts;
	private Metric reconnectionCount;
	
	private Metric avgInsertSizeBytes;
	private Metric maxInsertSizeBytes;
	private Metric minInsertSizeBytes;
	private Metric sumInsertSizeBytes;
	
	/**
	 * Mapper size plugin is installed.
	 */
	private boolean mapperSizeInstalled = false;
	
	/**
     * Initialize this operator and create Elasticsearch client to send get requests to.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context) throws Exception {
		super.initialize(context);
        logger.trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId());

        // Construct a new client config object
        config = getClientConfiguration();
        logger.debug(config.toString());
        // TODO remove after testing
        System.out.println(config.toString());
        
        // create client 
        // TODO add robust error checking here
        client = new JESTClient(config);
        client.setLogger(logger);
        client.init();
        rawClient = (JestClient) client.getRawClient();
	}

	/**
     * Convert incoming tuple attributes to JSON and output them to an Elasticsearch
     * database, configured in the operator's params.
     * Optionally, if 'indexName', 'typeName', and 'idName' attributes are detected 
     * in the tuple's schema, they will be used, instead.
     * will override 
     * @param stream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple) throws Exception {
    	
    	// Get attribute names.
		StreamSchema schema = tuple.getStreamSchema();
    	Set<String> attributeNames = schema.getAttributeNames();
    	
    	// Add attribute names/values to jsonDocuments.
    	JSONObject jsonDocuments = new JSONObject();
    	for(String attributeName : attributeNames) {
    		
    		// Skip attributes used explicitly for defining index, type, id, and timestamps.
    		if (indexNameAttribute != null && indexNameAttribute.getAttribute().getName().equals(attributeName)) {
    			continue;
    		} else if (typeNameAttribute != null && typeNameAttribute.getAttribute().getName().equals(attributeName)) {
    			continue;
    		} else if (idNameAttribute != null && idNameAttribute.getAttribute().getName().equals(attributeName)) {
    			continue;
    		} else if (timestampValueAttribute != null && timestampValueAttribute.getAttribute().getName().equals(attributeName)) {
    			continue;
    		}
    		
    		if (schema.getAttribute(attributeName).getType().getMetaType() == Type.MetaType.RSTRING) {
    			jsonDocuments.put(attributeName, tuple.getObject(attributeName).toString());
    		} else {
    			jsonDocuments.put(attributeName, tuple.getObject(attributeName));
    		}
    	}
    	
    	// Add timestamp, if enabled.
    	if (storeTimestamps) {
    		DateFormat df = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ss.SSSZZ");
    		
    		String timestampToInsert;
    		if (timestampValueAttribute != null) {
    			long timestamp = getTimestampValue(tuple).longValue();
    			timestampToInsert = df.format(new Date(timestamp));
    		} else {
    			timestampToInsert = df.format(new Date(System.currentTimeMillis()));
    		}
    		
    		jsonDocuments.put(timestampName, timestampToInsert);
    	}
        	
    	// Get index, type, and ID.
    	String indexToInsert = getIndex(tuple);
    	String typeToInsert = getType(tuple);
    	String idToInsert = getId(tuple);
    	
    	if (indexToInsert == null || typeToInsert == null) {
    		throw new Exception("Index and type must be defined.");
    	}
    	
    	if (connectedToElasticsearch(indexToInsert, typeToInsert)) {
        	
    		// Add jsonDocuments to bulkBuilder.
        	String source = jsonDocuments.toString();
        	
        	if (idToInsert != null) {
        		bulkBuilder.addAction(new Index.Builder(source).index(indexToInsert).type(typeToInsert).id(idToInsert).build());
        	} else {
        		bulkBuilder.addAction(new Index.Builder(source).index(indexToInsert).type(typeToInsert).build());
        	}
        	
        	currentBulkSize++;
    	
        	// If bulk size met, output jsonFields to Elasticsearch.
        	if(currentBulkSize >= bulkSize) {
	        	Bulk bulk = bulkBuilder.build();
	        	BulkResult result;
	        	
	        	try {
		        	result = rawClient.execute(bulk);
	        	} catch (NoHttpResponseException e) {
	        		logger.error(e);
	        		return;
	        	}
	        	
    			if (result.isSucceeded()) {
    				long currentNumInserts = numInserts.getValue();
    				numInserts.setValue(currentNumInserts + bulkSize);
    				currentBulkSize = 0;
    			} else {
    				for (BulkResultItem item : result.getItems()) {
    					if (item.error != null) {
    						numInserts.increment();
    					} else {
    						totalFailedRequests.increment();
    					}
    				}
    			}
    			
    			// Clear bulkBuilder. Gets recreated in connectedToElasticsearch().
    			bulkBuilder = null;
    			
    			// Get size metrics for current type.
    			if (sizeMetricsEnabled && mapperSizeInstalled) {
	    			getAndSetSizeMetrics(indexToInsert, typeToInsert, idToInsert);
    			}
    		}
    	}
    }

	/**
     * Shutdown this operator and close Elasticsearch API client.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        // Close connection to Elasticsearch server.
        client.close();
        super.shutdown();
    }
    
    /**
     * Get index from either the indexName or indexNameAttribute. indexNameAttribute 
     * overrides indexName.
     * @param tuple
     * @return
     */
    private String getIndex(Tuple tuple) {
    	if (indexNameAttribute != null) {
    		String index = indexNameAttribute.getValue(tuple);
    		if (!index.isEmpty()) {
    			return index;
    		}
    	} else if (indexName != null) {
    		return indexName;
    	}
    	return null;
    }
    
    /**
     * Get type from either the typeName or typeNameAttribute. typeNameAttribute 
     * overrides typeName.
     * @param tuple
     * @return
     */
    private String getType(Tuple tuple) {
    	if (typeNameAttribute != null) {
    		String index = typeNameAttribute.getValue(tuple);
    		if (!index.isEmpty()) {
    			return index;
    		}
    	} else if (typeName != null) {
    		return typeName;
    	}
    	return null;
    }
    
    /**
     * Get ID from either the idName or idNameAttribute. idNameAttribute 
     * overrides idName.
     * @param tuple
     * @return
     */
    private String getId(Tuple tuple) {
    	if (idNameAttribute != null) {
    		String index = idNameAttribute.getValue(tuple);
    		if (!index.isEmpty()) {
    			return index;
    		}
    	} else if (idName != null) {
    		return idName;
    	}
    	return null;
    }
    
    /**
     * Get timestamp from either the timestampName or timestampValueAttribute. timestampValueAttribute 
     * overrides timestampName.
     * @param tuple
     * @return
     */
    private Long getTimestampValue(Tuple tuple) {
    	if (timestampValueAttribute != null) {
    		return timestampValueAttribute.getValue(tuple);
    	}
    	return null;
    }
    
    /**
     * Get and set size metrics.
     * @param indexToInsert
     * @param typeToInsert
     * @param idToInsert
     * @throws IOException 
     */
    private void getAndSetSizeMetrics(String indexToInsert, String typeToInsert, String idToInsert) throws IOException {
    	String query = "{\n" +
                "    \"query\" : {\n" +
                "        \"match_all\" : {}\n" +
                "    },\n" +
                "    \"aggs\" : {\n" +
                "        \"size_metrics\" : {\n" +
                "            \"extended_stats\" : {\n" +
                "                \"field\" : \"_size\"\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}";
		
        Search search = new Search.Builder(query)
                .addIndex(indexToInsert)
                .addType(typeToInsert)
                .build();
        
        SearchResult searchResult = rawClient.execute(search);
        
        if (searchResult.isSucceeded()) {
	        ExtendedStatsAggregation sizeMetrics = searchResult.getAggregations().getExtendedStatsAggregation(SIZE_METRICS_PROPERTY);
			
	        if (sizeMetrics != null) {
	        	if (sizeMetrics.getAvg() != null) {
	    			avgInsertSizeBytes.setValue(sizeMetrics.getAvg().longValue());
	        	}
	        	if (sizeMetrics.getMax() != null) {
	        		maxInsertSizeBytes.setValue(sizeMetrics.getMax().longValue());
	        	}
	        	if (sizeMetrics.getMin() != null) {
	        		minInsertSizeBytes.setValue(sizeMetrics.getMin().longValue());
	        	}
	        	if (sizeMetrics.getSum() != null) {
	        		sumInsertSizeBytes.setValue(sizeMetrics.getSum().longValue());
	        	}
	        }
        }
    }
    
    /**
     * Check if operator is currently connected to Elasticsearch server. If not, try connecting.
     * @param client
     * @throws IOException 
     */
    private Boolean connectedToElasticsearch(String indexToInsert, String typeToInsert) throws IOException {
    	
    	// Keep trying to reconnect until reconnectionPolicyCount met.
    	int reconnectionAttempts = 0;
    	reconnectionCount.setValue(0);
    	while (reconnectionAttempts < config.getReconnectionPolicyCount()) {
	    	try {
				// Create index if it doesn't exist.
	    		boolean indexExists = rawClient.execute(new IndicesExists.Builder(indexToInsert).build()).isSucceeded();
				if (!indexExists) {
					JestResult result = rawClient.execute(new CreateIndex.Builder(indexToInsert).build());
					
					if (!result.isSucceeded()) {
						isConnected.setValue(0);
						return false;
					}
				}

				// Enable _size mapping.
				if (sizeMetricsEnabled) {
					SizeMapping sizeMapping = new SizeMapping.Builder(indexToInsert, typeToInsert, true).build();
					JestResult result = rawClient.execute(sizeMapping);
					
					if (result.isSucceeded()) {
						mapperSizeInstalled = true;
					} else {
						logger.error("Mapper size plugin was not detected. Please try restarting the Elasticsearch server after install.");
					}
				}

				// Reset bulkBuilder.
				if (bulkBuilder == null) {
					bulkBuilder = new Bulk.Builder()
								  .defaultIndex(indexToInsert)
								  .defaultType(typeToInsert);
				}
				
				isConnected.setValue(1);
				return true;
				
	        } catch (HttpHostConnectException e) {
	        	logger.error(e);
	        	
	        	isConnected.setValue(0);
	        	reconnectionAttempts++;
	        	reconnectionCount.increment();
	        }
    	}
    	
    	logger.error("Reconnection policy count, " + config.getReconnectionPolicyCount() + ", reached. Operator still not connected to server.");
    	return false;
    }
 
    // metrics ----------------------------------------------------------------------------------------------------------------
    
    /**
     * isConnected metric describes current connection status to Elasticsearch server.
     * @param isConnected
     */
    @CustomMetric(name = "isConnected", kind = Metric.Kind.GAUGE,
    		description = "Describes whether we are currently connected to Elasticsearch server.")
    public void setIsConnected(Metric isConnected) {
    	this.isConnected = isConnected;
    }
    
    /**
     * totalFailedRequests describes the number of failed inserts/gets over the lifetime of the operator.
     * @param totalFailedRequests
     */
    @CustomMetric(name = "totalFailedRequests", kind = Metric.Kind.COUNTER,
    		description = "The number of failed inserts/gets over the lifetime of the operator.")
    public void setTotalFailedRequests(Metric totalFailedRequests) {
    	this.totalFailedRequests = totalFailedRequests;
    }
    
    /**
     * numInserts metric describes the number of times a record has been successfully written.
     * @param numInserts
     */
    @CustomMetric(name = "numInserts", kind = Metric.Kind.COUNTER,
    		description = "The number of times a record has been written to the Elasticsearch server.")
    public void setNumInserts(Metric numInserts) {
    	this.numInserts = numInserts;
    }
    
    /**
     * isConnected metric describes current connection status to Elasticsearch server.
     * @param reconnectionCount
     */
    @CustomMetric(name = "reconnectionCount", kind = Metric.Kind.COUNTER,
    		description = "The number of times the operator has tried reconnecting to the server since the last successful connection.")
    public void setReconnectionCount(Metric reconnectionCount) {
    	this.reconnectionCount = reconnectionCount;
    }
    
    /**
     * The average size of inserted records, in bytes, within the current type.
     * @param avgInsertSizeBytes
     */
    @CustomMetric(name = "avgInsertSizeBytes", kind = Metric.Kind.GAUGE,
    		description = "The average size of inserted records, in bytes (aggregated over all documents within the current type).")
    public void setAvgInsertSizeBytes(Metric avgInsertSizeBytes) {
    	this.avgInsertSizeBytes = avgInsertSizeBytes;
    }
    
    /**
     * The maximum size of any inserted records, in bytes, within the current type.
     * @param maxInsertSizeBytes
     */
    @CustomMetric(name = "maxInsertSizeBytes", kind = Metric.Kind.GAUGE,
    		description = "The maximum size of any inserted records, in bytes (aggregated over all documents within the current type).")
    public void setMaxInsertSizeBytes(Metric maxInsertSizeBytes) {
    	this.maxInsertSizeBytes = maxInsertSizeBytes;
    }

    /**
     * The minimum size of any inserted records, in bytes, within the current type.
     * @param minInsertSizeBytes
     */
    @CustomMetric(name = "minInsertSizeBytes", kind = Metric.Kind.GAUGE,
    		description = "The minimum size of any inserted records, in bytes (aggregated over all documents within the current type).")
    public void setMinInsertSizeBytes(Metric minInsertSizeBytes) {
    	this.minInsertSizeBytes = minInsertSizeBytes;
    }
    
    /**
     * The total size of all inserted records, in bytes, within the current type.
     * @param sumInsertSizeBytes
     */
    @CustomMetric(name = "sumInsertSizeBytes", kind = Metric.Kind.GAUGE,
    		description = "The total size of all inserted records, in bytes (aggregated over all documents within the current type).")
    public void setSumInsertSizeBytes(Metric sumInsertSizeBytes) {
    	this.sumInsertSizeBytes = sumInsertSizeBytes;
    }
    
    // operator parameters setters ------------------------------------------------------------------------------------------------------
    
	@Parameter(name="indexName", optional=true,
		description="Specifies the name for the index."
	)
	public void setIndexName(String indexName) {
		this.indexName = indexName;
	}
	
	@Parameter(name="indexNameAttribute", optional=true,
		description="Specifies the attribute providing the index names."
	)
	public void setIndexNameAttribute(TupleAttribute<Tuple, String> indexNameAttribute) {
		this.indexNameAttribute = indexNameAttribute;
	}
	
	@Parameter(name="typeName", optional=true,
		description="Specifies the name for the type."
	)
	@Deprecated
	public void setTypeName(String typeName) {
		this.typeName = typeName;
	}
	
	@Parameter(name="typeNameAttribute", optional=true,
		description="Specifies the attribute providing the type names."
	)
	@Deprecated
	public void setTypeNameAttribute(TupleAttribute<Tuple, String> typeNameAttribute) {
		this.typeNameAttribute = typeNameAttribute;
	}
	
	@Parameter(name="idName", optional=true,
		description="Specifies the name for the id. If not specified, id is auto-generated."
	)
	public void setIdName(String idName) {
		this.idName = idName;
	}
	
	@Parameter(name="idNameAttribute", optional=true,
		description="Specifies the attribute providing the ID names."
	)
	public void setIdNameAttribute(TupleAttribute<Tuple, String> idNameAttribute) {
		this.idNameAttribute = idNameAttribute;
	}
	
	@Parameter(name="storeTimestamps", optional=true,
		description="Enables storing timestamps."
	)
	public void setStoreTimestamps(boolean storeTimestamps) {
		this.storeTimestamps = storeTimestamps;
	}
	
	@Parameter(name="timestampName", optional=true,
		description="Specifies the name for the timestamp attribute."
	)
	public void setTimestampName(String timestampName) {
		this.timestampName = timestampName;
	}
	
	@Parameter(name="timestampValueAttribute", optional=true,
		description="Specifies the attribute providing the timestamp values."
	)
	public void setTimestampValueAttribute(TupleAttribute<Tuple, Long> timestampValueAttribute) throws IOException {
		this.timestampValueAttribute = timestampValueAttribute;
	}
	
	@Parameter(name="bulkSize", optional=true,
		description="Specifies the size of the bulk to submit to Elasticsearch."
	)
	public void setBulkSize(int bulkSize) {
		this.bulkSize = bulkSize;
	}
	
	@Parameter(name="sizeMetricsEnabled", optional=true,
		description="Specifies whether to store and aggregate size metrics."
	)
	public void setSizeMetricsEnabled(boolean sizeMetricsEnabled) {
		this.sizeMetricsEnabled = sizeMetricsEnabled;
	}
   
    
	// operator and port documentation -------------------------------------------------------------------------------------------------------

	static final String operatorDescription = 
			"The ElasticsearchIndex operator receives incoming tuples and outputs "
			+ "the attribute's name-value pairs to an Elasticsearch database.\\n"
			+ "\\n"
			+ "The ElasticsearchIndex requires a hostname and hostport of an "
			+ "Elasticsearch server to connect to.\\n"
			+ "\\n"
			+ "By default, the hostname is 'localhost', and the hostport "
			+ "is 9200. This configuration can be changed in the parameters.\\n"
			+ "\\n"
			+ "An index and type must also be specified. The id is optional and if"
			+ "not specified, it is auto-generated.\\n"
			+ "\\n"
			+ "A timestampName can optionally be specified for time-based queries "
			+ "to create time-series charts that display how a tuple's attribute value "
			+ "changes over time.\\n"
			+ "\\n"
			+ "Once the data is outputted to Elasticsearch, the user can query the "
			+ "database and create custom graphs to display this data with graphing "
			+ "tools such as Grafana and Kibana.\\n"
			+ "\\n"
			;
	
	static final String iport0Description = "Port that ingests tuples"
			;
 
}
