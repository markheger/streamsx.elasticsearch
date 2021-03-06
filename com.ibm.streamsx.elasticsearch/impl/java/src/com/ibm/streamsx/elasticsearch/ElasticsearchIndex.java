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

import org.apache.log4j.Logger;

import com.ibm.json.java.JSONObject;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.TupleAttribute;
import com.ibm.streams.operator.Type;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.metrics.Metric;
import com.ibm.streams.operator.model.CustomMetric;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.state.Checkpoint;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streams.operator.state.StateHandler;
import com.ibm.streamsx.elasticsearch.client.Client;
import com.ibm.streamsx.elasticsearch.client.ClientMetrics;
import com.ibm.streamsx.elasticsearch.client.Configuration;
import com.ibm.streamsx.elasticsearch.client.JESTClient;
import com.ibm.streamsx.elasticsearch.i18n.Messages;
import com.ibm.streamsx.elasticsearch.util.StreamsHelper;

@PrimitiveOperator(name="ElasticsearchIndex", namespace="com.ibm.streamsx.elasticsearch", description=
	ElasticsearchIndex.operatorDescription +
	ElasticsearchIndex.indexCreation +
	ElasticsearchIndex.CR_DESC + 
	ElasticsearchIndex.CR_EXAMPLES_DESC
)
@InputPorts({@InputPortSet(
		id="0",
		description=ElasticsearchIndex.iport0Description,
		cardinality=1,
		optional=false,
		windowingMode=WindowMode.NonWindowed,
		windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious)
})
public class ElasticsearchIndex extends AbstractElasticsearchOperator implements StateHandler
{
	// operator parameter members --------------------------------------------------------------------------- 
	
	private int bulkSize = 1;
	
	private String indexName;
	private TupleAttribute<Tuple, String> indexNameAttribute;

	private String typeName;
	private TupleAttribute<Tuple, String> typeNameAttribute;

	private String idName;
	private TupleAttribute<Tuple, String> idNameAttribute;
	
	private boolean storeTimestamps = false;	
	private String timestampName = "timestamp";
	private TupleAttribute<Tuple, Long> timestampValueAttribute;
	
	// the input stream attribute name, that contains JSON document as string
	private TupleAttribute<Tuple, String> documentAttribute;	
	
	// internal members -------------------------------------------------------------------------------------
	
	/**
	 * Logger for tracing.
	 */
	private static Logger logger = Logger.getLogger(ElasticsearchIndex.class.getName());
	
	/**
	 * Elasticsearch Client API.
	 */
	private Client client;
	private int currentBulkSize = 0;
	private Configuration config = null;
	private ClientMetrics clientMetrics = null;
	
	private ConsistentRegionContext crContext;
	
	/**
	 * Metrics
	 */
	private Metric numInserts;
	
	/**
     * Initialize this operator and create Elasticsearch client to send get requests to.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context) throws Exception {
		super.initialize(context);
        logger.trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId());
 
        // get CR context
        crContext = context.getOptionalContext(ConsistentRegionContext.class);
        
        // Construct new client config and metrics objects
        config = getClientConfiguration();
        logger.info(config.toString());
        clientMetrics = ClientMetrics.getClientMetrics();

        // create client 
        client = new JESTClient(config, clientMetrics);
        client.setLogger(logger);
        
        // validate configuration, if this fails we stop the operator
        if (!client.validateConfiguration()) {
        	logger.error(Messages.getString("ELASTICSEARCH_INVALID_CONFIG"));
        	throw new RuntimeException("Client configuration is invalid");
        }
        	
        // initialize client, if this fails we stop the operator
        if (!client.init()) {
        	logger.error(Messages.getString("ELASTICSEARCH_INIT_FAILED"));
        	throw new RuntimeException("Client initialization failed");
        }
        
        updateMetrics(clientMetrics);
	}

	/**
     * Convert incoming tuple attributes to JSON and output them to an Elasticsearch
     * database, configured in the operator's params.
     * If 'documentAttribute' is set, then this attribute is used as JSON document.
     * Optionally, if 'indexName', 'typeName', and 'idName' attributes are detected 
     * in the tuple's schema, they will be used, instead.
     * will override 
     * @param stream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple) throws Exception {
    	String source = null;
    	if (documentAttribute != null) {
    		source = documentAttribute.getValue(tuple);
    	}
        else {
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
	    	
	    	// build json string
	    	source = jsonDocuments.toString();
    	}

    	// Get index, type, and ID.
    	String indexToInsert = getIndex(tuple);
    	String typeToInsert = getType(tuple);
    	String idToInsert = getId(tuple);
    	
    	if (indexToInsert == null || indexToInsert.equals("")) {
    		logger.error(Messages.getString("ELASTICSEARCH_UNKNOWN_INDEX"));
    		return;
    	}
    	
    	client.bulkIndexAddDocument(source,indexToInsert,typeToInsert,idToInsert);
    	currentBulkSize++;
    	
    	// send bulk if needed
    	if ((currentBulkSize == bulkSize) && (!isConsistentRegion())) {
    		client.bulkIndexSend();
    		currentBulkSize = 0;
    		updateMetrics(clientMetrics);
    	}
    }
    
	@Override
	public void processPunctuation(StreamingInput<Tuple> arg0, Punctuation punct)
			throws Exception {
		if (isConsistentRegion()) {
			// do not send bulk if consistent region is enabled, commit on drain instead
			super.processPunctuation(arg0, punct);
		}
		else {
			if (punct == Punctuation.FINAL_MARKER) {
				client.bulkIndexSend();
				currentBulkSize = 0;
	    		updateMetrics(clientMetrics);				
			}
			super.processPunctuation(arg0, punct);
		}
	}    

	protected void updateMetrics (ClientMetrics clientMetrics) {
		super.updateMetrics(clientMetrics);
		// handle numInserts metric here 
		this.numInserts.setValue(clientMetrics.getNumInserts());
	}    
    
	/**
     * Shutdown this operator and close Elasticsearch API client.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        logger.trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        // shutdown client
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
    
    // ConsistentRegion --------------------------------------------------------------------------------------
    
	private boolean isConsistentRegion() {
		if (crContext != null) {
			return true;
		}
		return false;
	}

	private void reset() {
		logger.debug("--> RESET currentBulkSize=" + currentBulkSize);
		// reset members
		currentBulkSize = 0;
		client.reset();
		logger.debug("<-- RESET");
	}	

	@Override
	public void close() throws IOException {
		// StateHandler implementation
	}	
	
	@Override
	public void checkpoint(Checkpoint checkpoint) throws Exception {
		// StateHandler implementation
		// nothing to checkpoint
	}

	@Override
	public void drain() throws Exception {
		// StateHandler implementation
		logger.debug("--> DRAIN currentBulkSize=" + currentBulkSize);
        long before = System.currentTimeMillis();

    	// send bulk if needed
    	client.bulkIndexSend();
    	currentBulkSize = 0;
    	updateMetrics(clientMetrics);

        long after = System.currentTimeMillis();
        final long duration = after - before;
        logger.debug("<-- DRAIN took " + duration + " ms");	
	}

	@Override
	public void reset(Checkpoint checkpoint) throws Exception {
		// StateHandler implementation
		reset();
		// nothing to restore from checkpoint
	}

	@Override
	public void resetToInitialState() throws Exception {
		// StateHandler implementation
		reset();
	}

	@Override
	public void retireCheckpoint(long id) throws Exception {
		// StateHandler implementation
	}    
    
    
	// checkers --------------------------------------------------------------------------------------
	
	@ContextCheck(compile = true)
    public static void compiletimeChecker(OperatorContextChecker checker) {
		StreamsHelper.checkCheckpointConfig(checker, "ElasticsearchIndex");
		StreamsHelper.checkConsistentRegion(checker, "ElasticsearchIndex");
		
		if (checker.getOperatorContext().getParameterNames().contains("storeTimestamps") &&
			checker.getOperatorContext().getParameterNames().contains("documentAttribute")) {
			checker.setInvalidContext(Messages.getString("ELASTICSEARCH_INVALID_PARAMETERS", "storeTimestamps", "documentAttribute"), null );
		}		
	}    

	@ContextCheck(compile = false, runtime = true)
    public static void runtimeChecker(OperatorContextChecker checker) {
		
		// check attribute types in input port 0
		OperatorContext ctx = checker.getOperatorContext();
		StreamSchema schema = ctx.getStreamingInputs().get(0).getStreamSchema();
		
		Set<String> attributeNames = schema.getAttributeNames();
		for (String attrName : attributeNames) {
			MetaType attrType = schema.getAttribute(attrName).getType().getMetaType();
			if (!(
					attrType == MetaType.BOOLEAN ||
					attrType == MetaType.FLOAT32 ||
					attrType == MetaType.FLOAT64 ||
					attrType == MetaType.INT16   ||
					attrType == MetaType.INT32   ||
					attrType == MetaType.INT64   ||
					attrType == MetaType.INT8    ||
					attrType == MetaType.RSTRING ||
					attrType == MetaType.USTRING ||
					attrType == MetaType.UINT16  ||
					attrType == MetaType.UINT32  ||
					attrType == MetaType.UINT64  ||
					attrType == MetaType.UINT8   ||
					attrType == MetaType.BSTRING
				)) {
				checker.setInvalidContext(Messages.getString("ELASTICSEARCH_UNSUPPORTED_ATTR_TYPE", attrName, attrType.toString()), null );
			}
		}
	}    
 
    // metrics ----------------------------------------------------------------------------------------------------------------
      
    /**
     * numInserts metric describes the number of times a record has been successfully written.
     * @param numInserts
     */
    @CustomMetric(name = "numInserts", kind = Metric.Kind.COUNTER,
    		description = "The number of times a record has been written to the Elasticsearch server.")
    public void setNumInserts(Metric numInserts) {
    	this.numInserts = numInserts;
    }
    
    // operator parameters setters ------------------------------------------------------------------------------------------------------
    
	@Parameter(name="indexName", optional=true,
		description="Specifies the name of the Elasticsearch index, the documents will be inserted to. "
		+ "If the index does not exist in the Elasticsearch server, it will be created by the server. However, you should create and configure indices by yourself "
		+ "before using them, to avoid automatic creation with properties that do not match the use case. For example unsuitable mapping or number of shards or replicas. "
		+ "This parameter will be ignored, if the 'indexNameAttribute' parameter is set. "
	)
	public void setIndexName(String indexName) {
		this.indexName = indexName;
	}
	
	@Parameter(name="indexNameAttribute", optional=true,
		description="Specifies the name of an attribute in the input tuple, containing the index name to insert the document to. "
		+ "It is not recommended to use this parameter because all documents created by an instance of the operator will have the same structure, "
		+ "so it might not be very useful to insert these documents into different indices. If you need to insert documents with the same structure "
		+ "to different indices, this can always be achieved by using multiple 'Elasticsearchindex' operator instances with different 'indexName' parameters "
		+ "and a Split operator in front of them, to route the douments. "
		+ "This parameter might be removed in the future."
	)
	public void setIndexNameAttribute(TupleAttribute<Tuple, String> indexNameAttribute) {
		this.indexNameAttribute = indexNameAttribute;
	}
	
	@Parameter(name="typeName", optional=true,
		description="Specifies the name of the mapping type for the document within the Elasticsearch index. If no type is specified the default type of '_doc' is used. "
		+ "This parameter will be ignored, if the 'typeNameAttribute' parameter is set. "
		+ "Because different mapping types for a single index are not allowed anymore in Elasticsearch version 6, and mapping types will be completely removed in ES7, "
		+ "it is recommended to not use this parameter. It might be removed in future versions of the toolkit. "
	)
	public void setTypeName(String typeName) {
		this.typeName = typeName;
	}
	
	@Parameter(name="typeNameAttribute", optional=true,
		description="Specifies the name of an attribute in the input tuple, containing the type mapping of the document to index. "
		+ "As different types per index is not allowed in ES6 anymore, and types will be removed from ES7 completely, it is not recommended to use this parameter. "
		+ "It will be removed in future versions of the toolkit. See also the 'typeName' parameter. "
	)
	public void setTypeNameAttribute(TupleAttribute<Tuple, String> typeNameAttribute) {
		this.typeNameAttribute = typeNameAttribute;
	}
	
	@Parameter(name="idName", optional=true,
		description="Specifies the name for the _id field of the document. If not specified, the _id field is auto-generated by the Elasticsearch server. "
		+ "This parameter is ignored if the 'idNameAttribute' parameter is specified. "
	)
	public void setIdName(String idName) {
		this.idName = idName;
	}
	
	@Parameter(name="idNameAttribute", optional=true,
		description="Specifies the name of an attribute in the input tuple, containing the _id of the document to index. "
		+ "If neither this parameter nor the 'idName' parameter is set, the document _id field is auto-generated by the Elasticsearch server. "
	)
	public void setIdNameAttribute(TupleAttribute<Tuple, String> idNameAttribute) {
		this.idNameAttribute = idNameAttribute;
	}
	
	@Parameter(name="storeTimestamps", optional=true,
		description="Enables storing timestamps. If enabled, either the current time or the timestamp contained in an attribute of the input tuple. "
		+ "will be added to the document. "
		+ "The default value is 'false'. "
	)
	public void setStoreTimestamps(boolean storeTimestamps) {
		this.storeTimestamps = storeTimestamps;
	}
	
	@Parameter(name="timestampName", optional=true,
		description="If parameter 'storeTimestamps' is true, this parameter specifies the name of the document attribute that will contain the timestamp. "
		+ "The timestamp is generated in the format 'yyyy-MM-ddTHH:mm:ss.SSSZZ' in Java SimpleDate notation. "
	)
	public void setTimestampName(String timestampName) {
		this.timestampName = timestampName;
	}
	
	@Parameter(name="timestampValueAttribute", optional=true,
		description="If parameter 'storeTimestamps' is true, this parameter specifies an attribute of type int64 in the input tuple containing the timestamp value in Unix format with milliseconds. "
		+ "If the parameter is not specified, the current time is used as timestamp value. "
	)
	public void setTimestampValueAttribute(TupleAttribute<Tuple, Long> timestampValueAttribute) throws IOException {
		this.timestampValueAttribute = timestampValueAttribute;
	}
	
	@Parameter(name="bulkSize", optional=true,
		description="Specifies the size of the bulk to submit to Elasticsearch. The default value is 1. When operator is part of consistent region, this parameter is ignored."
	)
	public void setBulkSize(int bulkSize) {
		this.bulkSize = bulkSize;
	}
	
	@Parameter(name="documentAttribute", optional=true,
		description="Specifies the name of an attribute in the input tuple, containing the document in JSON format to be inserted to. "
			+ "The parameter 'storeTimestamps' must not be set in conjunction with the 'documentAttribute' parameter."
	)
	public void setDocumentAttribute(TupleAttribute<Tuple, String> documentAttribute) {
		this.documentAttribute = documentAttribute;
	}
	
	// operator and port documentation -------------------------------------------------------------------------------------------------------

	static final String operatorDescription = 
			"The ElasticsearchIndex operator receives incoming tuples and stores the tuple attributes "
			+ "name-value pairs as JSON documents in a specified index of an Elasticsearch database. It uses the Elasticsearch REST API to connect to the server.\\n"
			+ "\\n"
			+ "The operator requires a hostname and hostport or a list of hostname/port pairs of an Elasticsearch cluster to connect to.\\n"
			+ "By default, the server is 'localhost', and the port is 9200. This configuration can be changed via the 'nodeList' parameter.\\n"
			+ "\\n"
			+ "An index to write the documents to must be specified by either using the 'indexName' or the 'indexNameAttribute' parameter. "
			+ "The document id can be specified by parameters. If the id is not specified, it is automatically generated by the Elasticsearch server. "
			+ "\\n"
			+ "A timestampName can optionally be specified for adding timestamps to the indexed document. This can help with time-based document queries. "
			+ "\\n"
			+ "Once the data is outputted to Elasticsearch, the user can query the "
			+ "database and create custom graphs to display this data with graphing "
			+ "tools such as Grafana and Kibana.\\n"
			+ "\\n"
			;
	
	static final String iport0Description =
			"Port that ingests tuples which are converted to JSON format and than stored in Elasticsearch as documents. "
			+ "Each attribute in the input schema will become an document attribute, the name of the JSON attribute will be the name of the "
			+ "Streams tuple attribute, the value will be taken from the attributes value. "
			+ "Some attributes may get special treatment to serve as document id, index name or timestamp provider. "
			+ "See parameteters 'indexNameAttribute', 'idNameAttribute', 'typeNameAttribute', 'timeStampValueAttribute' for details. "
			+ "These attributes will not become document attributes in the indexed Elasticsearch document. "
			+ "The following SPL attribute types are suppoted by the operator: boolean,rstring,ustring,bstring, all float types, all int and uint types. "
			+ "If the input port schema uses other types, the operator will not start and emit an error message for the attribute with the unsupported type. "
			;
 
	public static final String CR_DESC =
			"\\n"+
			"\\n+ Behavior in a consistent region\\n"+
			"\\n"+
			"\\nThe operator can participate in a consistent region. " +
			"The operator can be part of a consistent region, but cannot be at the start of a consistent region.\\n" +
			"\\nConsistent region supports that tuples are processed at least once.\\n" +
			"\\nFailures during tuple processing or drain are handled by the operator and consistent region support.\\n" +
			"\\nOn drain, the operator flushes its internal buffer and loads the documents to the Elasticsearch database.\\n" +
			"\\n# Restrictions\\n"+
			"\\nThe bulk size can not be configured when running in a consistent region. The parameter `bulkSize` is ignored and the bulk is submitted on drain."
		   	;

	public static final String CR_EXAMPLES_DESC =
			"\\n"+
			"\\n+ Example for guaranteed processing with exactly-once semantics\\n"+
			"\\nTo achieve exactly-once semantics with the ElasticsearchIndex operator:\\n" +
			"* The operator must be part of a consistent region. In case of failures tuples are replayed by the source operator.\\n" +
			"* In order to overwrite existing documents, ensure that the idNameAttribute parameter set, for an input stream attribute containing a unique key.\\n" +					
			"\\n\\n"+
			"\\nIn the sample below the ElasticsearchIndex operator reads Elasticsearch credentials from application configuration.\\n"+
			"Ensure that application configuration with name \\\"es\\\" has been created with the properties nodeList, userName and password.\\n"+
		    "\\n    composite Main {"+
		    "\\n        param"+
		    "\\n            expression<rstring> $indexName: getSubmissionTimeValue(\\\"indexName\\\", \\\"index-sample\\\");"+
			"\\n    "+
			"\\n        graph"+
			"\\n    "+
			"\\n            () as JCP = JobControlPlane() {}"+
			"\\n    "+
			"\\n            @consistent(trigger=periodic, period=5.0)"+
			"\\n            stream<rstring key, uint64 dummy> Documents = Beacon() {"+
			"\\n                param"+
			"\\n                    period: 0.01;"+
			"\\n                output"+
			"\\n                    Documents : key = \\\"SAMPLE\\\"+(rstring) IterationCount();"+
			"\\n            }"+
			"\\n    "+
			"\\n            () as ElasticsearchSink = ElasticsearchIndex(Documents) {"+
			"\\n                param"+
			"\\n                    indexName: $indexName;"+
			"\\n                    idNameAttribute: key;"+
			"\\n            }"+
			"\\n    }"+	
			"\\n"			
			;	

	public static final String indexCreation =
			"\\n"+
			"\\n+ Details on index creation\\n"+
			"\\n# Automatic index creation \\n"+
			"\\nIf you specify an index that does not already exists in the Elasticsearch server, it will be created automatically by the server. " +
			"\\nIn that case you have no control over the parameters of the index and the document field mapping. " +
			"\\nThere are several defaults that will be used for index creation, for details check the Elasticsearch documentation, here: " +					
			"\\n[https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-index.html|Create index] " +
			"\\nThe type mapping of document fields for new indices is determined by the 'dynamic mapping' rules of Elasticsearch. This feature is described in the documentation, for example here: " +
			"\\n[https://www.elastic.co/guide/en/elasticsearch/reference/current/dynamic-mapping.html|Dynamic Mapping] \\n" +
			"\\nThe dynamic mapping may not automatically recognise the desired data type for a field, for example the Elasticsearch geo_point type is not automatically detected, when you send a string field formatted as geo_point string. " +
			"In that case the geo_point will appear as normal text string in the Elasticsearch index, and may limit your ability to query the index. \\n" +
			"\\n**To take full control about the indices your application is using, it is recomended to manually create them before usage** \\n" +
			"\\n" +
			"# Manual index creation \\n" +
			"\\nIf you manually create the index before usage, you can specify all necessary parameters of the index. So you "+
			"do not have to rely on the automatic index creation of the Elasticsearch server. \\n"+
			"\\nMost likely you want to set these parameters \\n"+
			"* The number of shards for the index\\n"+
			"* The number of replicas for the index\\n"+
			"* The type mapping of the index \\n"+
			"\\nThe following example shows a curl command to create an index named 'test' with 2 shards and one replica. The schema for the index contains one field named 'locationName' of type text and one field "+
			"named 'location' of type geo_point'. \\n"+
			"\\n"+
			"\\n    curl -X PUT \\\"localhost:9200/test\\\" -H 'Content-Type: application/json' -d'"+
			"\\n    {"+
			"\\n        \\\"settings\\\" : {"+
			"\\n            \\\"number_of_shards\\\" : 2,"+
			"\\n            \\\"number_of_replicas\\\" : 1"+
			"\\n        },"+
			"\\n        \\\"mappings\\\" : {"+
			"\\n            \\\"_doc\\\" : {"+
			"\\n                \\\"properties\\\" : {"+
			"\\n                    \\\"locationName\\\" : { \\\"type\\\" : \\\"text\\\" },"+
			"\\n                    \\\"location\\\" : { \\\"type\\\" : \\\"geo_point\\\" }"+
			"\\n                }"+
			"\\n            }"+
			"\\n        }"+
			"\\n    }'"+
			"\\n\\n"
			;	
	
}
