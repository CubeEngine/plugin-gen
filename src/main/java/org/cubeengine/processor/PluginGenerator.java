/*
 * CubeEngine Plugin Generator - Generates necessary plugin glue code for CubeEngine modules.
 * Copyright © 2018 CubeEngine (development@cubeengine.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.cubeengine.processor;

import static javax.tools.StandardLocation.CLASS_OUTPUT;
import static org.cubeengine.processor.PluginGenerator.CORE_ANNOTATION;
import static org.cubeengine.processor.PluginGenerator.DEP_ANNOTATION;
import static org.cubeengine.processor.PluginGenerator.PLUGIN_ANNOTATION;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;

@SupportedOptions({"cubeengine.module.version", "cubeengine.module.sourceversion", "cubeengine.module.id", "cubeengine.module.name", "cubeengine.module.description", "cubeengine.module.team", "cubeengine.module.url", "cubeengine.module.libcube.version", "cubeengine.module.sponge.version"})
@SupportedAnnotationTypes({ PLUGIN_ANNOTATION, CORE_ANNOTATION, DEP_ANNOTATION })
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class PluginGenerator extends AbstractProcessor
{

    private static final String PACKAGE = "org.cubeengine.processor.";
    static final String PLUGIN_ANNOTATION = PACKAGE + "Module";
    static final String CORE_ANNOTATION = PACKAGE + "Core";
    static final String DEP_ANNOTATION = PACKAGE + "Dependency";

    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
    }

    private boolean generated = false;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv)
    {
        if (roundEnv.processingOver())
        {
            return false;
        }

        if (this.generated) {
            return false;
        }
        generateModulePlugin(roundEnv);
        generateCorePlugin(roundEnv);
        this.generated = true;

        return false;
    }

    private void generateCorePlugin(RoundEnvironment roundEnv)
    {
        for (Element el : roundEnv.getElementsAnnotatedWith(Core.class))
        {
            messager.printNote("Generating core plugin");
            buildSource((TypeElement) el, new ArrayList<>(), true);
        }
    }

    public void generateModulePlugin(RoundEnvironment roundEnv)
    {
        final Set<? extends Element> moduleSet = roundEnv.getElementsAnnotatedWith(Module.class);
        if (moduleSet.size() > 0) {
            messager.printNote("Generating %d modules".formatted(moduleSet.size()));
        }
        for (Element el : moduleSet)
        {
            final TypeElement element = (TypeElement) el;

            Module annotation = element.getAnnotation(Module.class);
            Dependency[] deps = annotation.dependencies();

            Dependency coreDep = getCoreDep();
            List<Dependency> allDeps = new ArrayList<>(Arrays.asList(deps));
            allDeps.add(coreDep);

            buildSource(element, allDeps, false);
        }
    }

    private void buildSource(TypeElement element, List<Dependency> allDeps, boolean core) {
        allDeps.add(getSpongeAPIDep());

        Name packageName = ((PackageElement) element.getEnclosingElement()).getQualifiedName();
        String pluginName = "Plugin" + element.getSimpleName();
        String simpleName = element.getSimpleName().toString();
        String moduleClass = packageName + "." + element.getSimpleName();
        String sourceVersion = processingEnv.getOptions().getOrDefault("cubeengine.module.sourceversion","unknown");
        if ("${githead.branch}-${githead.commit}".equals(sourceVersion))
        {
            sourceVersion = "unknown";
        }
        String version = processingEnv.getOptions().getOrDefault("cubeengine.module.version","unknown");
        String id = "cubeengine_" + processingEnv.getOptions().getOrDefault("cubeengine.module.id", element.getSimpleName().toString().toLowerCase());
        if (core) id = "cubeengine_core";
        String name = "CubeEngine - " + processingEnv.getOptions().getOrDefault("cubeengine.module.name","unknown");
        String description = processingEnv.getOptions().getOrDefault("cubeengine.module.description","unknown");
        String team = processingEnv.getOptions().getOrDefault("cubeengine.module.team","unknown") + " Team";
        String url = processingEnv.getOptions().getOrDefault("cubeengine.module.url","");

        try (BufferedWriter writer = newSourceFile(packageName, pluginName))
        {
            writer.write(String.format("""
                package %s;
                
                import com.google.inject.Inject;
                import com.google.inject.Injector;
                import org.spongepowered.plugin.builtin.jvm.Plugin;
                import org.spongepowered.api.event.Listener;
                import org.spongepowered.api.event.Order;
                import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
                import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
                import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
                import org.spongepowered.api.event.lifecycle.StartingEngineEvent;
                import org.spongepowered.api.Server;
                import org.spongepowered.api.command.Command;
                import org.cubeengine.libcube.CubeEnginePlugin;
                """, packageName));
            if (core)
            {
                writer.write("""
                        import org.apache.logging.log4j.Logger;
                        import com.google.inject.Injector;
                        import org.spongepowered.plugin.PluginContainer;
                        import org.cubeengine.libcube.CorePlugin;
                        import org.spongepowered.api.config.ConfigDir;
                        import java.nio.file.Path;
                        """);
            }
            else
            {
                writer.write("import org.cubeengine.libcube.LibCube;\n");
            }
            writer.write("import org.spongepowered.api.Sponge;\n");
            writer.write(String.format("import %s;\n\n", moduleClass));

            writer.write(String.format("""
                        @Plugin(%s.%s)
                        public class %s extends %s
                        {
                            public static final String %s = "%s";
                            public static final String %s = "%s";

                            %s
                            public %s(%s)
                            {
                                 super(%s);
                            }

                            public String sourceVersion()
                            {
                                return "%s";
                            }
                            
                            @Override @Listener
                            public void onConstruction(ConstructPluginEvent event) 
                            {
                                super.onConstruction(event);
                            }
                            
                            @Override @Listener(order = Order.EARLY)
                            public void onInit(StartingEngineEvent<Server> event)
                            {
                                super.onInit(event);
                            }
                            
                            @Override @Listener(order = Order.FIRST)
                            public void onStarted(StartedEngineEvent<Server> event)
                            {
                                super.onStarted(event);
                            }
                            
                            @Override @Listener
                            public void onRegisterCommand(final RegisterCommandEvent<Command.Parameterized> event)
                            {
                                super.onRegisterCommand(event);
                            }
                        }
                        """,
                    pluginName, simpleName.toUpperCase() + "_ID",
                    pluginName, core ? "CorePlugin" : "CubeEnginePlugin",
                    simpleName.toUpperCase() + "_ID", id,
                    simpleName.toUpperCase() + "_VERSION", version,
                    core ? "@Inject" : "",
                    pluginName, core ? "@ConfigDir(sharedRoot = true) Path path, Logger logger, Injector injector, PluginContainer container" : "",
                    core ? "path, logger, injector, container" : element.getSimpleName() + ".class",
                    sourceVersion));
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }

        try (BufferedWriter writer = newResourceFile("", "META-INF/sponge_plugins.json"))
        {
            final String jsonDeps = allDeps.stream().map(d -> String.format("""
                            {
                                "id": "%s",
                                "version": "%s",
                                "optional": %s
                            }""",
                            d.value(), d.version(), d.optional()))
                    .collect(Collectors.joining(",\n")).indent(8).stripTrailing();
            String plugin = String.format("""
                    {
                        "id": "%s",
                        "name": "%s",
                        "version": "%s",
                        "entrypoint": "%s",
                        "description": "%s",
                        "links": {
                            "homepage": "%s"
                        },
                        "contributors": [
                            {
                                "name": "%s"
                            }
                        ],
                        "dependencies": [
                    %s
                        ],
                        "properties": {
                            "source-version": "%s"
                        }
                    }""",
                    id, name, version, packageName + "." + pluginName, description,
                    url, // TODO source/issues url
                    team,
                    jsonDeps,
                    sourceVersion
            ).indent(8).stripTrailing();

            writer.write(String.format("""
                    {
                        "loader": {
                            "name": "%s",
                            "version": "%s"
                        },
                        "license": "%s",
                        "plugins": [
                    %s
                        ]
                    }
                    """,
                    "java_plain", "1.0",
                    "GPLv3",
                    plugin
            ));
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }

        try (BufferedWriter writer = newResourceFile("", "META-INF/MANIFEST.MF"))
        {
            writer.write("Manifest-Version: 1.0\n");
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    public Dependency getCoreDep() {
        return new Dependency() {
            @Override
            public String value() {
                return "cubeengine_core";
            }

            @Override
            public String version() {
                return processingEnv.getOptions().getOrDefault("cubeengine.module.libcube.version", "unknown");
            }

            @Override
            public boolean optional() {
                return false;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Dependency.class;
            }
        };
    }

    public Dependency getSpongeAPIDep() {
        return new Dependency() {
            @Override
            public String value() {
                return "spongeapi";
            }

            @Override
            public String version() {
                return processingEnv.getOptions().getOrDefault("cubeengine.module.sponge.version", "unknown");
            }

            @Override
            public boolean optional() {
                return false;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Dependency.class;
            }
        };
    }

    private BufferedWriter newSourceFile(Name packageName, String pluginName) throws IOException
    {
        String name = pluginName;
        if (!packageName.isEmpty()) {
            name = packageName + "." + name;
        }
        FileObject obj = this.processingEnv.getFiler().createSourceFile(name);
        return new BufferedWriter(obj.openWriter());
    }

    private BufferedWriter newResourceFile(String packageName, String fileName) throws IOException
    {
        FileObject obj = this.processingEnv.getFiler().createResource(CLASS_OUTPUT, packageName, fileName);
        return new BufferedWriter(obj.openWriter());
    }
}
