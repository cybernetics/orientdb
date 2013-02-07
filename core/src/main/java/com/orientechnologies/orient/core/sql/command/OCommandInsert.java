/*
 * Copyright 2013 Orient Technologies.
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.sql.command;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.core.command.OCommandDistributedConditionalReplicateRequest;
import com.orientechnologies.orient.core.command.OCommandExecutor;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.security.ODatabaseSecurityResources;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandExecutorSQLSetAware;
import com.orientechnologies.orient.core.sql.OCommandParameters;
import com.orientechnologies.orient.core.sql.filter.OSQLFilterItemField;
import com.orientechnologies.orient.core.sql.model.OUnset;
import com.orientechnologies.orient.core.sql.parser.OSQL;
import com.orientechnologies.orient.core.sql.parser.OSQLParser;
import com.orientechnologies.orient.core.sql.parser.SQLGrammarUtils;
import com.orientechnologies.orient.core.sql.parser.SyntaxException;
import com.orientechnologies.orient.core.sql.parser.UnknownResolverVisitor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * SQL INSERT command.
 * 
 * @author Luca Garulli
 * @author Johann Sorel (Geomatys)
 */
public class OCommandInsert extends OCommandExecutorSQLSetAware implements
    OCommandDistributedConditionalReplicateRequest {
  
  public static final String KEYWORD_INSERT = "INSERT";

  private String target;
  private String[] fields;
  private OProperty[] fieldProperties;
  private List<Object[]> newRecords;
  
  private String className = null;
  private String clusterName = null;
  private String indexName = null;

  public OCommandInsert(){}
  
  public OCommandInsert(String target, String cluster, List<String> fields, List<Object[]> records) {
    this.target = target;
    this.fields = fields.toArray(new String[fields.size()]);
    this.newRecords = records;
    this.clusterName = cluster;
  }

  @Override
  public <RET extends OCommandExecutor> RET parse(OCommandRequest iRequest) {
    final String sql = ((OCommandRequestText) iRequest).getText();
    final ParseTree tree = OSQL.compileExpression(sql);
    if(tree instanceof OSQLParser.CommandContext){
      try {
        visit((OSQLParser.CommandContext)tree);
      } catch (SyntaxException ex) {
        throw new OException(ex.getMessage(), ex);
      }
    }else{
      throw new OException("Parse error, query is not a valid INSERT INTO.");
    }
    
    final ODatabaseRecord database = getDatabase();
    database.checkSecurity(ODatabaseSecurityResources.COMMAND, ORole.PERMISSION_READ);
    
    if (target.startsWith(CLUSTER_PREFIX)) {
      // CLUSTER
      clusterName = target.substring(CLUSTER_PREFIX.length());

    } else if (target.startsWith(INDEX_PREFIX)) {
      // INDEX
      indexName = target.substring(INDEX_PREFIX.length());

    } else {
      // CLASS
      if (target.startsWith(CLASS_PREFIX)) {
        target = target.substring(CLASS_PREFIX.length());
      }

      final OClass cls = database.getMetadata().getSchema().getClass(target);
      if (cls == null) {
        throw new OException("Class " + target + " not found in database");
      }else{
        fieldProperties = new OProperty[fields.length];
        for(int i=0;i<fields.length;i++){
          fieldProperties[i] = cls.getProperty(fields[i]);
        }
      }

      className = cls.getName();
    }

    return (RET)this;
  }

  public String getTarget() {
    return target;
  }

  public String[] getFields() {
    return fields;
  }

  public List<Object[]> getRecords() {
    return newRecords;
  }
  
  @Override
  public Object execute(final Map<Object, Object> iArgs) {
    if(iArgs != null && !iArgs.isEmpty()){
      //we need to set value where we have OUnknowned
      final UnknownResolverVisitor visitor = new UnknownResolverVisitor(iArgs);
      for(Object[] newRecord : newRecords){
        for(int i=0;i<newRecord.length;i++){
          if(newRecord[i] instanceof OUnset){
            newRecord[i] = visitor.visit(((OUnset)newRecord[i]), null);
          }
        }
      }
    }
      
    
    final OCommandParameters commandParameters = new OCommandParameters(iArgs);
    if (indexName != null) {
      final OIndex<?> index = getDatabase().getMetadata().getIndexManager().getIndex(indexName);
      if (index == null)
        throw new OCommandExecutionException("Target index '" + indexName + "' not found");

      // BIND VALUES
      Map<String,Object> record = new HashMap<String, Object>();
      Map<String, Object> result = null;
      for (Object[] candidate : newRecords) {
        
        for(int i=0;i<candidate.length;i++){
          Object value = OSQL.evaluate(candidate[i]);
          record.put(fields[i], value );
        }
        index.put(getIndexKeyValue(commandParameters, record), getIndexValue(commandParameters, record));
        result = record;
      }

      // RETURN LAST ENTRY
      return new ODocument(result);
    } else {

      // CREATE NEW DOCUMENTS
      final List<ODocument> docs = new ArrayList<ODocument>();
      for (Object[] candidate : newRecords) {
        final ODocument doc = className != null ? new ODocument(className) : new ODocument();
        for(int i=0;i<candidate.length;i++){
          if(fieldProperties!=null && fieldProperties[i]!=null){
            doc.field(fields[i], OSQL.evaluate(candidate[i]), fieldProperties[i].getType());
          }else{
            doc.field(fields[i], OSQL.evaluate(candidate[i]));
          }
        }

        if (clusterName != null) {
          doc.save(clusterName);
        } else {
          doc.save();
        }
        docs.add(doc);
      }

      if (docs.size() == 1) {
        return docs.get(0);
      } else {
        return docs;
      }
    }
  }
  
  private Object getIndexKeyValue(OCommandParameters commandParameters, Map<String, Object> candidate) {
    final Object parsedKey = candidate.get(KEYWORD_KEY);
    if (parsedKey instanceof OSQLFilterItemField) {
      final OSQLFilterItemField f = (OSQLFilterItemField) parsedKey;
      if (f.getRoot().equals("?"))
        // POSITIONAL PARAMETER
        return commandParameters.getNext();
      else if (f.getRoot().startsWith(":"))
        // NAMED PARAMETER
        return commandParameters.getByName(f.getRoot().substring(1));
    }
    return parsedKey;
  }

  private OIdentifiable getIndexValue(OCommandParameters commandParameters, Map<String, Object> candidate) {
    final Object parsedRid = candidate.get(KEYWORD_RID);
    if (parsedRid instanceof OSQLFilterItemField) {
      final OSQLFilterItemField f = (OSQLFilterItemField) parsedRid;
      if (f.getRoot().equals("?"))
        // POSITIONAL PARAMETER
        return (OIdentifiable) commandParameters.getNext();
      else if (f.getRoot().startsWith(":"))
        // NAMED PARAMETER
        return (OIdentifiable) commandParameters.getByName(f.getRoot().substring(1));
    }
    return (OIdentifiable) parsedRid;
  }

  public boolean isReplicated() {
    return indexName != null;
  }

  @Override
  public String getSyntax() {
    return "INSERT INTO [class:]<class>|cluster:<cluster>|index:<index> [(<field>[,]*) VALUES (<expression>[,]*)[,]*]|[SET <field> = <expression>|<sub-command>[,]*]";
  }
  
  // GRAMMAR PARSING ///////////////////////////////////////////////////////////
  
  
  private void visit(OSQLParser.CommandContext candidate) throws SyntaxException {
    final Object commandTree = candidate.getChild(0);
    if(commandTree instanceof OSQLParser.CommandInsertIntoByValuesContext){
      visit((OSQLParser.CommandInsertIntoByValuesContext)commandTree);
    }else if(commandTree instanceof OSQLParser.CommandInsertIntoBySetContext){
      visit((OSQLParser.CommandInsertIntoBySetContext)commandTree);
    }else{
      throw new OException("Unknowned command " + candidate.getClass()+" "+candidate);
    }
  }
  
  private void visit(OSQLParser.CommandInsertIntoByValuesContext candidate) throws SyntaxException{    
    //variables
    String target;
    final List<String> fields = new ArrayList<String>();
    final List<Object[]> entries = new ArrayList<Object[]>();
    
    //parsing
    target = candidate.word().getText();
    if(candidate.CLUSTER() != null){
      target = "CLUSTER:"+target;
    }else  if(candidate.INDEX()!= null){
      target = "INDEX:"+target;
    }
    
    for(OSQLParser.WordContext wc : candidate.insertFields().word()){
      fields.add(wc.getText());
    }
    for(OSQLParser.InsertEntryContext entry : candidate.insertEntry()){
      final List<OSQLParser.ExpressionContext> exps = entry.expression();
      final Object[] values = new Object[exps.size()];
      for(int i=0;i<values.length;i++){
        values[i] = SQLGrammarUtils.visit(exps.get(i));
      }
      entries.add(values);
    }
    String cluster = null;
    if(candidate.insertCluster() != null){
      cluster = candidate.insertCluster().word().getText();
    }
    
    this.target = target;
    this.clusterName = cluster;
    this.fields = fields.toArray(new String[0]);
    this.newRecords = entries;
  }
  
  private void visit(OSQLParser.CommandInsertIntoBySetContext candidate) throws SyntaxException{    
    //variables
    final String target;
    final List<String> fields = new ArrayList<String>();
    final List<Object> values = new ArrayList<Object>();
    
    //parsing
    target = candidate.word().getText();
    for(OSQLParser.InsertSetContext entry : candidate.insertSet()){
      final String att = entry.word().getText();
      fields.add(att);
      final OSQLParser.ExpressionContext exp = entry.expression();
      values.add(SQLGrammarUtils.visit(exp));
    }
    
    final List<Object[]> entries = new ArrayList<Object[]>();
    entries.add(values.toArray());
    
    String cluster = null;
    if(candidate.insertCluster()!= null){
      cluster = candidate.insertCluster().word().getText();
    }
    
    this.target = target;
    this.clusterName = cluster;
    this.fields = fields.toArray(new String[0]);
    this.newRecords = entries;
  }
  
}
