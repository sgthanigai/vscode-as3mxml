/*
Copyright 2016-2019 Bowler Hat LLC

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
package com.as3mxml.vscode.providers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.as3mxml.vscode.project.WorkspaceFolderData;
import com.as3mxml.vscode.utils.ASTUtils;
import com.as3mxml.vscode.utils.CompilerProjectUtils;
import com.as3mxml.vscode.utils.LanguageServerCompilerUtils;
import com.as3mxml.vscode.utils.MXMLDataUtils;
import com.as3mxml.vscode.utils.WorkspaceFolderManager;

import org.apache.royale.compiler.common.ISourceLocation;
import org.apache.royale.compiler.definitions.IClassDefinition;
import org.apache.royale.compiler.definitions.IDefinition;
import org.apache.royale.compiler.internal.mxml.MXMLData;
import org.apache.royale.compiler.internal.projects.RoyaleProject;
import org.apache.royale.compiler.internal.units.ResourceBundleCompilationUnit;
import org.apache.royale.compiler.internal.units.SWCCompilationUnit;
import org.apache.royale.compiler.mxml.IMXMLDataManager;
import org.apache.royale.compiler.mxml.IMXMLLanguageConstants;
import org.apache.royale.compiler.mxml.IMXMLTagAttributeData;
import org.apache.royale.compiler.mxml.IMXMLTagData;
import org.apache.royale.compiler.scopes.IASScope;
import org.apache.royale.compiler.tree.as.IASNode;
import org.apache.royale.compiler.tree.as.IIdentifierNode;
import org.apache.royale.compiler.units.ICompilationUnit;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public class ReferencesProvider
{
    private static final String MXML_EXTENSION = ".mxml";

	private WorkspaceFolderManager workspaceFolderManager;

	public ReferencesProvider(WorkspaceFolderManager workspaceFolderManager)
	{
		this.workspaceFolderManager = workspaceFolderManager;
	}

	public List<? extends Location> references(ReferenceParams params, CancelChecker cancelToken)
	{
		cancelToken.checkCanceled();
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		Path path = LanguageServerCompilerUtils.getPathFromLanguageServerURI(textDocument.getUri());
		if (path == null)
		{
			cancelToken.checkCanceled();
			return Collections.emptyList();
		}
		WorkspaceFolderData folderData = workspaceFolderManager.getWorkspaceFolderDataForSourceFile(path);
		if(folderData == null || folderData.project == null)
		{
			cancelToken.checkCanceled();
			return Collections.emptyList();
		}
		RoyaleProject project = folderData.project;

		int currentOffset = workspaceFolderManager.getOffsetFromPathAndPosition(path, position, folderData);
		if (currentOffset == -1)
		{
			cancelToken.checkCanceled();
			return Collections.emptyList();
		}
		MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);

		IMXMLTagData offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
		if (offsetTag != null)
		{
			IASNode embeddedNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, currentOffset, folderData);
			if (embeddedNode != null)
			{
				List<? extends Location> result = actionScriptReferences(embeddedNode, project);
				cancelToken.checkCanceled();
				return result;
			}
			//if we're inside an <fx:Script> tag, we want ActionScript lookup,
			//so that's why we call isMXMLTagValidForCompletion()
			if (MXMLDataUtils.isMXMLCodeIntelligenceAvailableForTag(offsetTag))
			{
				ICompilationUnit offsetUnit = CompilerProjectUtils.findCompilationUnit(path, project);
				List<? extends Location> result = mxmlReferences(offsetTag, currentOffset, offsetUnit, project);
				cancelToken.checkCanceled();
				return result;
			}
		}
		IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
		List<? extends Location> result = actionScriptReferences(offsetNode, project);
		cancelToken.checkCanceled();
		return result;
	}

    private List<? extends Location> actionScriptReferences(IASNode offsetNode, RoyaleProject project)
    {
        if (offsetNode == null)
        {
            //we couldn't find a node at the specified location
            return Collections.emptyList();
        }

        if (offsetNode instanceof IIdentifierNode)
        {
            IIdentifierNode identifierNode = (IIdentifierNode) offsetNode;
            IDefinition resolved = identifierNode.resolve(project);
            if (resolved == null)
            {
                return Collections.emptyList();
            }
            List<Location> result = new ArrayList<>();
            referencesForDefinition(resolved, project, result);
            return result;
        }

        //VSCode may call definition() when there isn't necessarily a
        //definition referenced at the current position.
        return Collections.emptyList();
    }

    private List<? extends Location> mxmlReferences(IMXMLTagData offsetTag, int currentOffset, ICompilationUnit offsetUnit, RoyaleProject project)
    {
        IDefinition definition = MXMLDataUtils.getDefinitionForMXMLNameAtOffset(offsetTag, currentOffset, project);
        if (definition != null)
        {
            if (MXMLDataUtils.isInsideTagPrefix(offsetTag, currentOffset))
            {
                //ignore the tag's prefix
                return Collections.emptyList();
            }
            ArrayList<Location> result = new ArrayList<>();
            referencesForDefinition(definition, project, result);
            return result;
        }

        //finally, check if we're looking for references to a tag's id
        IMXMLTagAttributeData attributeData = MXMLDataUtils.getMXMLTagAttributeWithValueAtOffset(offsetTag, currentOffset);
        if (attributeData == null || !attributeData.getName().equals(IMXMLLanguageConstants.ATTRIBUTE_ID))
        {
            //VSCode may call definition() when there isn't necessarily a
            //definition referenced at the current position.
            return Collections.emptyList();
        }
        Collection<IDefinition> definitions = null;
        try
        {
            definitions = offsetUnit.getFileScopeRequest().get().getExternallyVisibleDefinitions();
        }
        catch (Exception e)
        {
            //safe to ignore
        }
        if (definitions == null || definitions.size() == 0)
        {
            return Collections.emptyList();
        }
        IClassDefinition classDefinition = null;
        for (IDefinition currentDefinition : definitions)
        {
            if (currentDefinition instanceof IClassDefinition)
            {
                classDefinition = (IClassDefinition) currentDefinition;
                break;
            }
        }
        if (classDefinition == null)
        {
            //this probably shouldn't happen, but check just to be safe
            return Collections.emptyList();
        }
        IASScope scope = classDefinition.getContainedScope();
        for (IDefinition currentDefinition : scope.getAllLocalDefinitions())
        {
            if (currentDefinition.getBaseName().equals(attributeData.getRawValue()))
            {
                definition = currentDefinition;
                break;
            }
        }
        if (definition == null)
        {
            //VSCode may call definition() when there isn't necessarily a
            //definition referenced at the current position.
            return Collections.emptyList();
        }
        ArrayList<Location> result = new ArrayList<>();
        referencesForDefinition(definition, project, result);
        return result;
    }

    private void referencesForDefinition(IDefinition definition, RoyaleProject project, List<Location> result)
    {
        for (ICompilationUnit compilationUnit : project.getCompilationUnits())
        {
            if (compilationUnit == null
                    || compilationUnit instanceof SWCCompilationUnit
                    || compilationUnit instanceof ResourceBundleCompilationUnit)
            {
                continue;
            }
            referencesForDefinitionInCompilationUnit(definition, compilationUnit, project, result);
        }
    }
    
    private void referencesForDefinitionInCompilationUnit(IDefinition definition, ICompilationUnit compilationUnit, RoyaleProject project, List<Location> result)
    {
        if (compilationUnit.getAbsoluteFilename().endsWith(MXML_EXTENSION))
        {
            IMXMLDataManager mxmlDataManager = workspaceFolderManager.compilerWorkspace.getMXMLDataManager();
            MXMLData mxmlData = (MXMLData) mxmlDataManager.get(workspaceFolderManager.fileSpecGetter.getFileSpecification(compilationUnit.getAbsoluteFilename()));
            IMXMLTagData rootTag = mxmlData.getRootTag();
            if (rootTag != null)
            {
                ArrayList<ISourceLocation> units = new ArrayList<>();
                MXMLDataUtils.findMXMLUnits(mxmlData.getRootTag(), definition, project, units);
                for (ISourceLocation otherUnit : units)
                {
                    Location location = LanguageServerCompilerUtils.getLocationFromSourceLocation(otherUnit);
                    if (location == null)
                    {
                        continue;
                    }
                    result.add(location);
                }
            }
        }
        IASNode ast = ASTUtils.getCompilationUnitAST(compilationUnit);
        if(ast == null)
        {
            return;
        }
        ArrayList<IIdentifierNode> identifiers = new ArrayList<>();
        ASTUtils.findIdentifiersForDefinition(ast, definition, project, identifiers);
        for (IIdentifierNode otherNode : identifiers)
        {
            Location location = LanguageServerCompilerUtils.getLocationFromSourceLocation(otherNode);
            if (location == null)
            {
                continue;
            }
            result.add(location);
        }
    }
}