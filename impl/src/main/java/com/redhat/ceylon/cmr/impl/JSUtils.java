/*
 * Copyright 2011 Red Hat inc. and third party contributors as noted
 * by the author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.ceylon.cmr.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.redhat.ceylon.cmr.api.AbstractDependencyResolver;
import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.ArtifactResult;
import com.redhat.ceylon.cmr.api.DependencyContext;
import com.redhat.ceylon.cmr.api.ModuleDependencyInfo;
import com.redhat.ceylon.cmr.api.ModuleInfo;
import com.redhat.ceylon.cmr.api.ModuleVersionArtifact;
import com.redhat.ceylon.cmr.api.ModuleVersionDetails;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.common.ModuleUtil;

import net.minidev.json.JSONValue;

/**
 * Utility functions to retrieve module meta information from compiled JS modules
 *
 * @author <a href="mailto:tako@ceylon-lang.org">Tako Schotanus</a>
 */
public final class JSUtils extends AbstractDependencyResolver implements ModuleInfoReader {
    public static JSUtils INSTANCE = new JSUtils();

    private JSUtils() {
    }

    public ModuleInfo resolve(DependencyContext context) {
        if (context.ignoreInner()) {
            return null;
        }

        ArtifactResult result = context.result();
        File mod = result.artifact();
        if (mod != null && (mod.getName().toLowerCase().endsWith(ArtifactContext.JS_MODEL)
                || mod.getName().toLowerCase().endsWith(ArtifactContext.JS))) {
            return readModuleInformation(result.name(), mod);
        } else {
            return null;
        }
    }
    
    @Override
    public ModuleInfo resolveFromFile(File file) {
        throw new UnsupportedOperationException("Operation not supported for .js files");
    }

    @Override
    public ModuleInfo resolveFromInputStream(InputStream stream) {
        throw new UnsupportedOperationException("Operation not supported for .js files");
    }

    public Node descriptor(Node artifact) {
        return null; // artifact is a descriptor
    }

    /**
     * Read module info from JS file
     *
     * @param moduleName the module name
     * @param jarFile    the module JS file
     * @return module info list
     */
    public static ModuleInfo readModuleInformation(final String moduleName, final File jarFile) {
        Map<String, Object> model = loadJsonModel(jarFile);
        return getDependencies(model);
    }

    public int[] getBinaryVersions(String moduleName, File moduleArchive) {
        int major = 0;
        int minor = 0;
        ModuleVersionDetails mvd = readModuleInfo(moduleName, moduleArchive, false);
        ModuleVersionArtifact mva = mvd.getArtifactTypes().first();
        if (mva.getMajorBinaryVersion() != null) {
            major = mva.getMajorBinaryVersion();
        }
        if (mva.getMinorBinaryVersion() != null) {
            minor = mva.getMinorBinaryVersion();
        }
        
        return new int[]{major, minor};
    }

    private static ScriptEngine getEngine(File moduleArchive) {
        ScriptEngine engine;
        try {
            engine = new ScriptEngineManager().getEngineByName("JavaScript");
            engine.eval("var exports={}");
            engine.eval("var module={}");
            engine.eval("function require() { return { '$addmod$' : function() {} } }");
            engine.eval(new FileReader(moduleArchive));
            return engine;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse module JS file", ex);
        }
    }

    private static ModuleInfo getDependencies(Map<String,Object> model) {
        try {
            return asModInfos(metaModelProperty(model, "$mod-deps"));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse module JS file", ex);
        }
    }
    
    @Override
    public ModuleVersionDetails readModuleInfo(String moduleName, File moduleArchive, boolean includeMembers) {
        Map<String, Object> model = loadJsonModel(moduleArchive);

        String name = asString(metaModelProperty(model, "$mod-name"));
        if (!moduleName.equals(name)) {
            throw new RuntimeException("Incorrect module");
        }
        String version = asString(metaModelProperty(model, "$mod-version"));
        ModuleInfo info = getDependencies(model);
        
        String type = ArtifactContext.getSuffixFromFilename(moduleArchive.getName());

        Integer major = null, minor = null;
        String bin = asString(metaModelProperty(model, "$mod-bin"));
        if (bin != null) {
            int p = bin.indexOf('.');
            if (p >= 0) {
                major = Integer.parseInt(bin.substring(0, p));
                minor = Integer.parseInt(bin.substring(p + 1));
            } else {
                major = Integer.parseInt(bin);
            }
        }
        ModuleVersionDetails mvd = new ModuleVersionDetails(moduleName, version);
        mvd.getArtifactTypes().add(new ModuleVersionArtifact(type, major, minor));
        mvd.getDependencies().addAll(info.getDependencies());
        
        if (includeMembers) {
            mvd.setMembers(getMembers(moduleName, moduleArchive));
        }
        
        return mvd;
    }

    private Set<String> getMembers(String moduleName, File moduleArchive) {
        // TODO Implement this!
        throw new RuntimeException("Not implemented yet");
    }
    
    private static Object metaModelProperty(Map<String,Object> model, String propName) {
        return model.get(propName);
    }
    
    private static String asString(Object obj) {
        if (obj == null) {
            return null;
        } else {
            return obj.toString();
        }
    }

    private static ModuleInfo asModInfos(Object obj) {
        if (obj == null) {
            return new ModuleInfo(null, Collections.<ModuleDependencyInfo>emptySet());
        }
        if (!(obj instanceof Iterable)) {
            throw new RuntimeException("Expected something Iterable");
        }
        @SuppressWarnings("unchecked")
        Iterable<Object> array = (Iterable<Object>)obj;
        Set<ModuleDependencyInfo> result = new HashSet<ModuleDependencyInfo>();
        for (Object o : array) {
            String module;
            boolean optional = false;
            boolean exported = false;
            if (o instanceof String) {
                module = asString(o);
            } else {
                @SuppressWarnings("unchecked")
                Map<String,Object> m = (Map<String,Object>)o;
                module = m.get("path").toString();
                optional = m.containsKey("opt");
                exported = m.containsKey("exp");
            }
            String name = ModuleUtil.moduleName(module);
            if (!"ceylon.language".equals(name)) {
                result.add(new ModuleDependencyInfo(name, ModuleUtil.moduleVersion(module), optional, exported));
            }
        }
        return new ModuleInfo(null, result);
    }

    public boolean matchesModuleInfo(String moduleName, File moduleArchive, String query) {
        ModuleVersionDetails mvd = readModuleInfo(moduleName, moduleArchive, false);
        if (mvd.getDoc() != null && matches(mvd.getDoc(), query))
            return true;
        if (mvd.getLicense() != null && matches(mvd.getLicense(), query))
            return true;
        for (String author : mvd.getAuthors()) {
            if (matches(author, query))
                return true;
        }
        for (ModuleDependencyInfo dep : mvd.getDependencies()) {
            if (matches(dep.getModuleName(), query))
                return true;
        }
        return false;
    }

    private static boolean matches(String string, String query) {
        return string.toLowerCase().contains(query);
    }

    private static Map<String,Object> loadJsonModel(File jsFile) {
        try {
            // If what we have is a plain .js file (not a -model.js file)
            // we first check if a model file exists and if so we use that
            // one instead of the given file
            String name = jsFile.getName().toLowerCase();
            if (!name.endsWith(ArtifactContext.JS_MODEL) && name.endsWith(ArtifactContext.JS)) {
                name = jsFile.getName();
                name = name.substring(0, name.length() - 3) + ArtifactContext.JS_MODEL;
                File modelFile = new File(jsFile.getParentFile(), name);
                if (modelFile.isFile()) {
                    jsFile = modelFile;
                }
            }
            Map<String, Object> model = readJsonModel(jsFile);
            if (model == null) {
                throw new RuntimeException("Unable to read meta model from file " + jsFile);
            }
            return model;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    /** Find the metamodel declaration in a js file, parse it as a Map and return it. 
     * @throws IOException */
    public static Map<String,Object> readJsonModel(File jsFile) throws IOException {
        // IMPORTANT
        // This method NEEDS to be able to return the meta model of any previous file formats!!!
        // It MUST stay backward compatible
        try (BufferedReader reader = new BufferedReader(new FileReader(jsFile))) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                if ((line.startsWith("ex$.$CCMM$=")
                        || line.startsWith("var $CCMM$=")
                        || line.startsWith("var $$METAMODEL$$=")
                        || line.startsWith("var $$metamodel$$=")) && line.endsWith("};")) {
                    line = line.substring(line.indexOf("{"), line.length()-1);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rv = (Map<String,Object>) JSONValue.parse(line);
                    return rv;
                }
            }
            return null;
        }
    }

}
