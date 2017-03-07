/*
Copyright 2016 Bowler Hat LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.nextgenactionscript.vscode.project;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.flex.compiler.internal.mxml.MXMLNamespaceMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;

/**
 * Configures a project using an asconfig.json file.
 */
public class ASConfigProjectConfigStrategy implements IProjectConfigStrategy
{
    private Path asconfigPath;
    private boolean changed = true;

    public ASConfigProjectConfigStrategy()
    {

    }

    public Path getASConfigPath()
    {
        return asconfigPath;
    }

    public void setASConfigPath(Path value)
    {
        asconfigPath = value;
    }

    public boolean getChanged()
    {
        return changed;
    }

    public void setChanged(boolean value)
    {
        changed = value;
    }

    public ProjectOptions getOptions()
    {
        changed = false;
        if (asconfigPath == null)
        {
            return null;
        }
        File asconfigFile = asconfigPath.toFile();
        if (!asconfigFile.exists())
        {
            return null;
        }
        Path projectRoot = asconfigPath.getParent();
        ProjectType type = ProjectType.APP;
        String config = null;
        String[] files = null;
        String additionalOptions = null;
        CompilerOptions compilerOptions = new CompilerOptions();
        try (InputStream schemaInputStream = getClass().getResourceAsStream("/schemas/asconfig.schema.json"))
        {
            JsonSchemaFactory factory = new JsonSchemaFactory();
            JsonSchema schema = factory.getSchema(schemaInputStream);
            String contents = FileUtils.readFileToString(asconfigFile);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(contents);
            Set<ValidationMessage> errors = schema.validate(json);
            if (!errors.isEmpty())
            {
                System.err.println("Failed to validate asconfig.json.");
                for (ValidationMessage error : errors)
                {
                    System.err.println(error.toString());
                }
                return null;
            }
            else
            {
                if (json.has(ProjectOptions.TYPE)) //optional, defaults to "app"
                {
                    String typeString = json.get(ProjectOptions.TYPE).asText();
                    type = ProjectType.fromToken(typeString);
                }
                config = json.get(ProjectOptions.CONFIG).asText();
                if (json.has(ProjectOptions.FILES)) //optional
                {
                    JsonNode jsonFiles = json.get(ProjectOptions.FILES);
                    int fileCount = jsonFiles.size();
                    files = new String[fileCount];
                    for (int i = 0; i < fileCount; i++)
                    {
                        String pathString = jsonFiles.get(i).asText();
                        Path filePath = projectRoot.resolve(pathString);
                        files[i] = filePath.toString();
                    }
                }
                if (json.has(ProjectOptions.COMPILER_OPTIONS)) //optional
                {
                    JsonNode jsonCompilerOptions = json.get(ProjectOptions.COMPILER_OPTIONS);
                    if (jsonCompilerOptions.has(CompilerOptions.DEBUG))
                    {
                        compilerOptions.debug = jsonCompilerOptions.get(CompilerOptions.DEBUG).asBoolean();
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.DEFINE))
                    {
                        HashMap<String, String> defines = new HashMap<>();
                        JsonNode jsonDefine = jsonCompilerOptions.get(CompilerOptions.DEFINE);
                        for (int i = 0, count = jsonDefine.size(); i < count; i++)
                        {
                            JsonNode jsonNamespace = jsonDefine.get(i);
                            String name = jsonNamespace.get(CompilerOptions.DEFINE_NAME).asText();
                            Object value = jsonNamespace.get(CompilerOptions.DEFINE_VALUE).asText();
                            if (value instanceof String)
                            {
                                value = "\"" + value + "\"";
                            }
                            defines.put(name, value.toString());
                        }
                        compilerOptions.defines = defines;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.EXTERNAL_LIBRARY_PATH))
                    {
                        JsonNode jsonExternalLibraryPath = jsonCompilerOptions.get(CompilerOptions.EXTERNAL_LIBRARY_PATH);
                        ArrayList<File> externalLibraryPath = new ArrayList<>();
                        for (int i = 0, count = jsonExternalLibraryPath.size(); i < count; i++)
                        {
                            String pathString = jsonExternalLibraryPath.get(i).asText();
                            Path filePath = projectRoot.resolve(pathString);
                            externalLibraryPath.add(filePath.toFile());
                        }
                        compilerOptions.externalLibraryPath = externalLibraryPath;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.INCLUDE_CLASSES))
                    {
                        JsonNode jsonIncludeClasses = jsonCompilerOptions.get(CompilerOptions.INCLUDE_CLASSES);
                        ArrayList<String> includeClasses = new ArrayList<>();
                        for (int i = 0, count = jsonIncludeClasses.size(); i < count; i++)
                        {
                            String qualifiedName = jsonIncludeClasses.get(i).asText();
                            includeClasses.add(qualifiedName);
                        }
                        compilerOptions.includeClasses = includeClasses;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.INCLUDE_NAMESPACES))
                    {
                        JsonNode jsonIncludeNamespaces = jsonCompilerOptions.get(CompilerOptions.INCLUDE_NAMESPACES);
                        ArrayList<String> includeNamespaces = new ArrayList<>();
                        for (int i = 0, count = jsonIncludeNamespaces.size(); i < count; i++)
                        {
                            String namespaceURI = jsonIncludeNamespaces.get(i).asText();
                            includeNamespaces.add(namespaceURI);
                        }
                        compilerOptions.includeNamespaces = includeNamespaces;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.INCLUDE_SOURCES))
                    {
                        JsonNode jsonIncludeSources = jsonCompilerOptions.get(CompilerOptions.INCLUDE_SOURCES);
                        ArrayList<File> includeSources = new ArrayList<>();
                        for (int i = 0, count = jsonIncludeSources.size(); i < count; i++)
                        {
                            String pathString = jsonIncludeSources.get(i).asText();
                            Path filePath = projectRoot.resolve(pathString);
                            includeSources.add(filePath.toFile());
                        }
                        compilerOptions.includeSources = includeSources;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.JS_OUTPUT_TYPE))
                    {
                        String jsonJSOutputType = jsonCompilerOptions.get(CompilerOptions.JS_OUTPUT_TYPE).asText();
                        compilerOptions.jsOutputType = jsonJSOutputType;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.NAMESPACE))
                    {
                        JsonNode jsonLibraryPath = jsonCompilerOptions.get(CompilerOptions.NAMESPACE);
                        ArrayList<MXMLNamespaceMapping> namespaceMappings = new ArrayList<>();
                        for (int i = 0, count = jsonLibraryPath.size(); i < count; i++)
                        {
                            JsonNode jsonNamespace = jsonLibraryPath.get(i);
                            String uri = jsonNamespace.get(CompilerOptions.NAMESPACE_URI).asText();
                            String manifest = jsonNamespace.get(CompilerOptions.NAMESPACE_MANIFEST).asText();
                            MXMLNamespaceMapping mapping = new MXMLNamespaceMapping(uri, manifest);
                            namespaceMappings.add(mapping);
                        }
                        compilerOptions.namespaceMappings = namespaceMappings;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.LIBRARY_PATH))
                    {
                        JsonNode jsonLibraryPath = jsonCompilerOptions.get(CompilerOptions.LIBRARY_PATH);
                        ArrayList<File> libraryPath = new ArrayList<>();
                        for (int i = 0, count = jsonLibraryPath.size(); i < count; i++)
                        {
                            String pathString = jsonLibraryPath.get(i).asText();
                            Path filePath = projectRoot.resolve(pathString);
                            libraryPath.add(filePath.toFile());
                        }
                        compilerOptions.libraryPath = libraryPath;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.SOURCE_PATH))
                    {
                        JsonNode jsonSourcePath = jsonCompilerOptions.get(CompilerOptions.SOURCE_PATH);
                        ArrayList<File> sourcePath = new ArrayList<>();
                        for (int i = 0, count = jsonSourcePath.size(); i < count; i++)
                        {
                            String pathString = jsonSourcePath.get(i).asText();
                            Path filePath = projectRoot.resolve(pathString);
                            sourcePath.add(filePath.toFile());
                        }
                        compilerOptions.sourcePath = sourcePath;
                    }
                    if (jsonCompilerOptions.has(CompilerOptions.WARNINGS))
                    {
                        compilerOptions.warnings = jsonCompilerOptions.get(CompilerOptions.WARNINGS).asBoolean();
                    }
                }
                //these options are formatted as if sent in through the command line
                if (json.has(ProjectOptions.ADDITIONAL_OPTIONS)) //optional
                {
                    additionalOptions = json.get(ProjectOptions.ADDITIONAL_OPTIONS).asText();
                }
            }
        }
        catch (Exception e)
        {
            System.err.println("Failed to parse asconfig.json: " + e);
            e.printStackTrace();
            return null;
        }
        //in a library project, the files field will be treated the same as the
        //include-sources compiler option
        if (type == ProjectType.LIB && files != null)
        {
            if (compilerOptions.includeSources == null)
            {
                compilerOptions.includeSources = new ArrayList<>();
            }
            for (int i = 0, count = files.length; i < count; i++)
            {
                String filePath = files[i];
                compilerOptions.includeSources.add(new File(filePath));
            }
            files = null;
        }
        ProjectOptions options = new ProjectOptions();
        options.type = type;
        options.config = config;
        options.files = files;
        options.compilerOptions = compilerOptions;
        options.additionalOptions = additionalOptions;
        return options;
    }
}