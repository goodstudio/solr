/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.schema;
import java.io.File;
import java.io.FileInputStream;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.solr.SolrTestUtil;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.AbstractBadConfigTestBase;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@LuceneTestCase.Nightly // MRM TODO: debug, got slow
public class TestManagedSchema extends AbstractBadConfigTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static File tmpSolrHome;
  private static File tmpConfDir;

  private static final String collection = "collection1";
  private static final String confDir = collection + "/conf";
  
  @Before
  private void initManagedSchemaCore() throws Exception {
    tmpSolrHome = SolrTestUtil.createTempDir().toFile();
    tmpConfDir = new File(tmpSolrHome, confDir);
    File testHomeConfDir = new File(SolrTestUtil.TEST_HOME(), confDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "solrconfig-managed-schema.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "solrconfig-basic.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "solrconfig-managed-schema-test.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "solrconfig.snippet.randomindexconfig.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "schema-one-field-no-dynamic-field.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "schema-one-field-no-dynamic-field-unique-key.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "schema-minimal.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "schema_codec.xml"), tmpConfDir);
    FileUtils.copyFileToDirectory(new File(testHomeConfDir, "schema-bm25.xml"), tmpConfDir);

    // initCore will trigger an upgrade to managed schema, since the solrconfig has
    // <schemaFactory class="ManagedIndexSchemaFactory" ... />
    System.setProperty("managed.schema.mutable", "false");
    System.setProperty("enable.update.log", "false");
  }

  @After
  private void afterClass() throws Exception {
    deleteCore();
    System.clearProperty("managed.schema.mutable");
    System.clearProperty("enable.update.log");
  }
  
  public void testUpgrade() throws Exception {
    initCore("solrconfig-managed-schema.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    assertTrue(managedSchemaFile.exists());
    String managedSchema = FileUtils.readFileToString(managedSchemaFile, "UTF-8");
    assertTrue(managedSchema.contains("DO NOT EDIT"));
    File upgradedOriginalSchemaFile = new File(tmpConfDir, "schema-minimal.xml.bak");
    assertTrue(upgradedOriginalSchemaFile.exists());
    assertSchemaResource(collection, "managed-schema");
    deleteCore();
  }
  
  public void testUpgradeThenRestart() throws Exception {
    initCore("solrconfig-managed-schema.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    assertSchemaResource(collection, "managed-schema");
    deleteCore();
    File nonManagedSchemaFile = new File(tmpConfDir, "schema-minimal.xml");
    assertFalse(nonManagedSchemaFile.exists());
    initCore("solrconfig-managed-schema.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    assertTrue(managedSchemaFile.exists());
    String managedSchema = FileUtils.readFileToString(managedSchemaFile, "UTF-8");
    assertTrue(managedSchema.contains("DO NOT EDIT"));
    File upgradedOriginalSchemaFile = new File(tmpConfDir, "schema-minimal.xml.bak");
    assertTrue(upgradedOriginalSchemaFile.exists());
    assertSchemaResource(collection, "managed-schema");
    deleteCore();
  }

  public void testUpgradeThenRestartNonManaged() throws Exception {
    // After upgrade to managed schema, fail to restart when solrconfig doesn't contain
    // <schemaFactory class="ManagedIndexSchemaFactory">...</schemaFactory>
    initCore("solrconfig-managed-schema.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    assertConfigs("solrconfig-basic.xml", "schema-minimal.xml", tmpSolrHome.getPath(),
                  "Can't find resource 'schema-minimal.xml'");
    deleteCore();
  }

  public void testUpgradeThenRestartNonManagedAfterPuttingBackNonManagedSchema() throws Exception {
    initCore("solrconfig-managed-schema-test.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    File nonManagedSchemaFile = new File(tmpConfDir, "schema-minimal.xml");
    assertFalse(nonManagedSchemaFile.exists());
    File upgradedOriginalSchemaFile = new File(tmpConfDir, "schema-minimal.xml.bak");
    assertTrue(upgradedOriginalSchemaFile.exists());

    deleteCore();
    // After upgrade to managed schema, downgrading to non-managed should work after putting back the non-managed schema.
    FileUtils.moveFile(upgradedOriginalSchemaFile, nonManagedSchemaFile);
    initCore("solrconfig-basic.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    assertSchemaResource(collection, "schema-minimal.xml");
    deleteCore();
  }

  public void testDefaultSchemaFactory() throws Exception {
    initCore("solrconfig-managed-schema-test.xml", "schema-minimal.xml", tmpSolrHome.getPath());

    final CoreContainer cores = h.getCoreContainer();
    final CoreAdminHandler admin = new CoreAdminHandler(cores);
    SolrQueryRequest request = req(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.STATUS.toString());
    SolrQueryResponse response = new SolrQueryResponse();
    admin.handleRequestBody(request, response);
    assertNull("Exception on create", response.getException());
    assertSchemaResource(collection, "managed-schema");
    request.close();
    deleteCore();
  }
  
  private void assertSchemaResource(String collection, String expectedSchemaResource) throws Exception {
    final CoreContainer cores = h.getCoreContainer();
    final CoreAdminHandler admin = new CoreAdminHandler(cores);
    SolrQueryRequest request = req(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.STATUS.toString());
    SolrQueryResponse response = new SolrQueryResponse();
    admin.handleRequestBody(request, response);
    assertNull("Exception on create", response.getException());
    NamedList responseValues = response.getValues();
    NamedList status = (NamedList)responseValues.get("status");
    NamedList collectionStatus = (NamedList)status.get(collection);
    String collectionSchema = (String)collectionStatus.get(CoreAdminParams.SCHEMA);
    assertEquals("Schema resource name differs from expected name", expectedSchemaResource, collectionSchema);
    request.close();
  }

  public void testAddFieldWhenNotMutable() throws Exception {
    initCore("solrconfig-managed-schema.xml", "schema-minimal.xml", tmpSolrHome.getPath());
    String errString = "This ManagedIndexSchema is not mutable.";
    ignoreException(Pattern.quote(errString));
    try (SolrCore core = h.getCore()) {
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldName = "new_field";
      String fieldType = "string";
      Map<String,?> options = Collections.emptyMap();
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);
      fail();
    } catch (Exception e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        // short circuit out if we found what we expected
        if (t.getMessage() != null && t.getMessage().contains(errString)) return;
      }
      // otherwise, rethrow it, possibly completely unrelated
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                              "Unexpected error, expected error matching: " + errString + " but got " + e.getMessage(), e);
    } finally {
      deleteCore();
      resetExceptionIgnores();
    }
  }
  
  public void testAddFieldPersistence() throws Exception {

    File managedSchemaFile = new File(tmpConfDir, "managed-schema");

    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome.getPath());
    
    assertTrue(managedSchemaFile.exists());
    String managedSchemaContents = FileUtils.readFileToString(managedSchemaFile, "UTF-8");
    assertFalse(managedSchemaContents.contains("\"new_field\""));

    try (SolrCore core = h.getCore()) {
      Map<String,Object> options = new HashMap<>();
      options.put("stored", "false");
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldName = "new_field";
      String fieldType = "string";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);
    }

    assertTrue(managedSchemaFile.exists());
    FileInputStream stream = new FileInputStream(managedSchemaFile);
    managedSchemaContents = IOUtils.toString(stream, "UTF-8");
    stream.close(); // Explicitly close so that Windows can delete this file
    assertTrue(managedSchemaContents.contains("<field name=\"new_field\" type=\"string\" stored=\"false\"/>"));

    deleteCore();
  }
  
  public void testAddedFieldIndexableAndQueryable() throws Exception {
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome.getPath());

    assertTrue(managedSchemaFile.exists());
    String managedSchemaContents = FileUtils.readFileToString(managedSchemaFile, "UTF-8");
    assertFalse(managedSchemaContents.contains("\"new_field\""));

    clearIndex();

    String errString = "unknown field 'new_field'";
    ignoreException(Pattern.quote(errString));
    try {
      assertU(adoc("new_field", "thing1 thing2", "str", "X"));
      fail();
    } catch (Exception e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        // short circuit out if we found what we expected
        if (t.getMessage() != null && -1 != t.getMessage().indexOf(errString)) return;
      }
      // otherwise, rethrow it, possibly completely unrelated
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "Unexpected error, expected error matching: " + errString, e);
    } finally {
      resetExceptionIgnores();
    }
    assertU(commit());
    assertQ(req("new_field:thing1"), "//*[@numFound='0']");

    try (SolrCore core = h.getCore()) {
      Map<String,Object> options = new HashMap<>();
      options.put("stored", "false");
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldName = "new_field";
      String fieldType = "text";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);
    }

    assertU(adoc("new_field", "thing1 thing2", "str", "X"));
    assertU(commit());

    assertQ(req("new_field:thing1"), "//*[@numFound='1']");
    deleteCore();
  }
  
  public void testAddFieldWhenItAlreadyExists() throws Exception {
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome.getPath());
    String errString = "Field 'str' already exists.";
    try (SolrCore core = h.getCore()) {
      assertNotNull("Field 'str' is not present in the schema", core.getLatestSchema().getFieldOrNull("str"));

      ignoreException(Pattern.quote(errString));

      Map<String,Object> options = new HashMap<>();
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldName = "str";
      String fieldType = "string";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);
      fail("Should fail when adding a field that already exists");
    } catch (Exception e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        // short circuit out if we found what we expected
        if (t.getMessage() != null && -1 != t.getMessage().indexOf(errString)) return;
      }
      // otherwise, rethrow it, possibly completely unrelated
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unexpected error, expected error matching: " + errString, e);
    } finally {
      resetExceptionIgnores();
      deleteCore();
    }
  }

  public void testAddSameFieldTwice() throws Exception {
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome.getPath());

    Map<String,Object> options = new HashMap<>();
    options.put("stored", "false");
    try (SolrCore core = h.getCore()) {
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldName = "new_field";
      String fieldType = "text";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);

      String errString = "Field 'new_field' already exists.";
      ignoreException(Pattern.quote(errString));
      try {
        newSchema = newSchema.addField(newField);

        core.setLatestSchema(newSchema);

        fail("Should fail when adding the same field twice");
      } catch (Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
          // short circuit out if we found what we expected
          if (t.getMessage() != null && -1 != t.getMessage().indexOf(errString)) return;
        }
        // otherwise, rethrow it, possibly completely unrelated
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unexpected error, expected error matching: " + errString, e);
      }
    } finally {
      resetExceptionIgnores();
      deleteCore();
    }
  }

  public void testAddDynamicField() throws Exception {
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome.getPath());
    try (SolrCore core = h.getCore()) {
      assertNull("Field '*_s' is present in the schema", core.getLatestSchema().getFieldOrNull("*_s"));
    }

    String errString = "Can't add dynamic field '*_s'.";
    ignoreException(Pattern.quote(errString));
    try {
      Map<String,Object> options = new HashMap<>();
      try (SolrCore core = h.getCore()) {
        IndexSchema oldSchema = core.getLatestSchema();
        String fieldName = "*_s";
        String fieldType = "string";
        SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
        IndexSchema newSchema = oldSchema.addField(newField);

        core.setLatestSchema(newSchema);
      }
      fail("Should fail when adding a dynamic field");
    } catch (Exception e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        // short circuit out if we found what we expected
        if (t.getMessage() != null && -1 != t.getMessage().indexOf(errString)) return;
      }
      // otherwise, rethrow it, possibly completely unrelated
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unexpected error, expected error matching: " + errString, e);
    } finally {
      resetExceptionIgnores();
      deleteCore();
    }
  }
  
  public void testAddWithSchemaCodecFactory() throws Exception {
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema_codec.xml", tmpSolrHome.getPath());
    try (SolrCore core = h.getCore()) {


      String uniqueKey = "string_f";
      assertNotNull("Unique key field '" + uniqueKey + "' is not present in the schema", core.getLatestSchema().getFieldOrNull(uniqueKey));

      String fieldName = "string_disk_new_field";

      assertNull("Field '" + fieldName + "' is present in the schema", core.getLatestSchema().getFieldOrNull(fieldName));


      Map<String,Object> options = new HashMap<>();
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldType = "string_disk";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);

      assertU(adoc(fieldName, "thing", uniqueKey, "aBc"));
      assertU(commit());

      assertQ(req(fieldName + ":thing"), "//*[@numFound='1']");
    }
    deleteCore();
  }

  public void testAddWithSchemaSimilarityFactory() throws Exception {

    File managedSchemaFile = new File(tmpConfDir, "managed-schema");

    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-bm25.xml", tmpSolrHome.getPath());
    try (SolrCore core = h.getCore()) {
      String uniqueKey = "id";
      assertNotNull("Unique key field '" + uniqueKey + "' is not present in the schema", core.getLatestSchema().getFieldOrNull(uniqueKey));

      String fieldName = "new_text_field";
      assertNull("Field '" + fieldName + "' is present in the schema", core.getLatestSchema().getFieldOrNull(fieldName));

      Map<String,Object> options = new HashMap<>();
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldType = "text";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);

      assertU(adoc(fieldName, "thing", uniqueKey, "123"));
      assertU(commit());

      assertQ(req(fieldName + ":thing"), "//*[@numFound='1']");
    }
    deleteCore();
  }

  public void testPersistUniqueKey() throws Exception {

    File managedSchemaFile = new File(tmpConfDir, "managed-schema");

    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field-unique-key.xml", tmpSolrHome.getPath());
    assertTrue(managedSchemaFile.exists());
    String managedSchemaContents = FileUtils.readFileToString(managedSchemaFile, "UTF-8");
    try (SolrCore core = h.getCore()) {

      assertFalse(managedSchemaContents.contains("\"new_field\""));

      Map<String,Object> options = new HashMap<>();
      options.put("stored", "false");
      IndexSchema oldSchema = core.getLatestSchema();
      assertEquals("str", oldSchema.getUniqueKeyField().getName());
      String fieldName = "new_field";
      String fieldType = "string";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      assertEquals("str", newSchema.getUniqueKeyField().getName());
      core.setLatestSchema(newSchema);
    }
    log.info("####close harness");
    h.close();
    log.info("####close harness end");
    initCore();

    assertTrue(managedSchemaFile.exists());
    FileInputStream stream = new FileInputStream(managedSchemaFile);
    managedSchemaContents = IOUtils.toString(stream, "UTF-8");
    stream.close(); // Explicitly close so that Windows can delete this file
    assertTrue(managedSchemaContents.contains("<field name=\"new_field\" type=\"string\" stored=\"false\"/>"));
    try (SolrCore core = h.getCore()) {
      IndexSchema newNewSchema = core.getLatestSchema();
      assertNotNull(newNewSchema.getUniqueKeyField());
      assertEquals("str", newNewSchema.getUniqueKeyField().getName());
    }

    deleteCore();
  }

  public void testAddFieldThenReload() throws Exception {
    File managedSchemaFile = new File(tmpConfDir, "managed-schema");

    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome.getPath());
    try (SolrCore core = h.getCore()) {
      String fieldName = "new_text_field";
      assertNull("Field '" + fieldName + "' is present in the schema", core.getLatestSchema().getFieldOrNull(fieldName));

      Map<String,Object> options = new HashMap<>();
      IndexSchema oldSchema = core.getLatestSchema();
      String fieldType = "text";
      SchemaField newField = oldSchema.newField(fieldName, fieldType, options);
      IndexSchema newSchema = oldSchema.addField(newField);
      core.setLatestSchema(newSchema);

      h.reload();
    }

    deleteCore();
  }
}
