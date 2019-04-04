/*
 * Copyright 2019. the original author or authors.
 *
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

package ms.dew.devops.kernel;

import com.ecfront.dew.common.$;
import ms.dew.devops.helper.DockerHelper;
import ms.dew.devops.helper.GitHelper;
import ms.dew.devops.helper.KubeHelper;
import ms.dew.devops.helper.YamlHelper;
import ms.dew.devops.kernel.config.*;
import ms.dew.devops.mojo.BasicMojo;
import ms.dew.notification.NotifyConfig;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.twdata.maven.mojoexecutor.MojoExecutor;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

public class Dew {

    public static boolean stopped = false;
    public static Log log;

    private static MavenSession mavenSession;
    private static BuildPluginManager mavenPluginManager;

    public static class Init {

        private static final AtomicBoolean initialized = new AtomicBoolean(false);

        public static void init(MavenSession session, BuildPluginManager pluginManager,
                                String profile,
                                String dockerHost, String dockerRegistryUrl,
                                String dockerRegistryUserName, String dockerRegistryPassword,
                                String kubeBase64Config,
                                String customVersion, String mockClasspath)
                throws IllegalAccessException, IOException, InvocationTargetException {
            Dew.mavenSession = session;
            Dew.mavenPluginManager = pluginManager;
            if (profile == null) {
                profile = BasicMojo.FLAG_DEW_DEVOPS_DEFAULT_PROFILE;
            }
            log.info("Active profile : " + profile);
            if (!initialized.getAndSet(true)) {
                GitHelper.init(log);
                YamlHelper.init(log);
                initFinalConfig(profile, dockerHost, dockerRegistryUrl, dockerRegistryUserName, dockerRegistryPassword, kubeBase64Config, customVersion);
                Config.getProjects().values().forEach(config -> {
                    DockerHelper.init(config.getId(), log, config.getDocker().getHost(),
                            config.getDocker().getRegistryUrl(), config.getDocker().getRegistryUserName(), config.getDocker().getRegistryPassword());
                    KubeHelper.init(config.getId(), log, config.getKube().getBase64Config());
                });
                initNotify();
                initMock(mockClasspath);
            }
            if (Config.getCurrentProject() != null) {
                if (Config.getCurrentProject().getKube().getBase64Config() == null
                        || Config.getCurrentProject().getKube().getBase64Config().isEmpty()) {
                    throw new RuntimeException("Kubernetes config can't be empty");
                }
            }
        }

        private static void initFinalConfig(String profile,
                                            String dockerHost, String dockerRegistryUrl,
                                            String dockerRegistryUserName, String dockerRegistryPassword,
                                            String kubeBase64Config, String customVersion)
                throws IOException, InvocationTargetException, IllegalAccessException {
            String basicDirectory = mavenSession.getTopLevelProject().getBasedir().getPath() + File.separator;
            String basicConfig = "";
            if (new File(basicDirectory + ".dew").exists()) {
                basicConfig = ConfigBuilder.mergeProfiles($.file.readAllByPathName(basicDirectory + ".dew", "UTF-8")) + "\r\n";
            }
            for (MavenProject project : mavenSession.getProjects()) {
                String projectDirectory = project.getBasedir().getPath() + File.separator;
                String projectConfig;
                if (!basicDirectory.equals(projectDirectory) && new File(projectDirectory + ".dew").exists()) {
                    projectConfig = ConfigBuilder.mergeProject(basicConfig,
                            ConfigBuilder.mergeProfiles($.file.readAllByPathName(projectDirectory + ".dew", "UTF-8")));
                } else {
                    projectConfig = basicConfig;
                }
                DewConfig dewConfig;
                if (!projectConfig.isEmpty()) {
                    dewConfig = YamlHelper.toObject(DewConfig.class, projectConfig);
                } else {
                    dewConfig = new DewConfig();
                }
                if (!profile.equalsIgnoreCase(BasicMojo.FLAG_DEW_DEVOPS_DEFAULT_PROFILE) && !dewConfig.getProfiles().containsKey(profile)) {
                    throw new IOException("Can't be found [" + profile + "] profile at " + project.getArtifactId());
                }
                if (profile.equalsIgnoreCase(BasicMojo.FLAG_DEW_DEVOPS_DEFAULT_PROFILE) && dewConfig.isSkip()
                        || !profile.equalsIgnoreCase(BasicMojo.FLAG_DEW_DEVOPS_DEFAULT_PROFILE) && dewConfig.getProfiles().get(profile).isSkip()) {
                    // 配置为跳过
                    continue;
                }
                if (profile.equalsIgnoreCase(BasicMojo.FLAG_DEW_DEVOPS_DEFAULT_PROFILE)) {
                    if (dewConfig.getKind() == null) {
                        dewConfig.setKind(Dew.Utils.checkAppKind(project));
                    }
                    if (dewConfig.getKind() == null) {
                        // 不支持的类型
                        continue;
                    }
                } else {
                    if (dewConfig.getProfiles().get(profile).getKind() == null) {
                        dewConfig.getProfiles().get(profile).setKind(Dew.Utils.checkAppKind(project));
                    }
                    if (dewConfig.getProfiles().get(profile).getKind() == null) {
                        // 不支持的类型
                        continue;
                    }
                }
                Config.config.getProjects().put(project.getId(),
                        ConfigBuilder.buildProject(profile, dewConfig, project,
                                dockerHost, dockerRegistryUrl, dockerRegistryUserName, dockerRegistryPassword, kubeBase64Config, customVersion));
                log.debug("[" + project.getGroupId() + ":" + project.getArtifactId() + "] configured");
            }
        }

        private static void initNotify() {
            Map<String, NotifyConfig> configMap = Dew.Config.getProjects().entrySet().stream()
                    .filter(config -> config.getValue().getNotify() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, config -> config.getValue().getNotify()));
            ms.dew.notification.Notify.init(configMap, flag -> "");
        }

        private static void initMock(String mockClasspath) {
            if (mockClasspath == null || mockClasspath.trim().isEmpty()) {
                return;
            }
            log.warn("Discover mock configuration, mock class path is " + mockClasspath);
            try {
                Mock.loadClass(mockClasspath);
                Mock.invokeMock();
            } catch (NoSuchMethodException
                    | InvocationTargetException
                    | MalformedURLException
                    | ClassNotFoundException
                    | IllegalAccessException
                    | InstantiationException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public static class Config {

        private static FinalConfig config = new FinalConfig();

        public static FinalProjectConfig getCurrentProject() {
            return config.getProjects().get(mavenSession.getCurrentProject().getId());
        }

        public static MavenProject getCurrentMavenProject() {
            return Dew.mavenSession.getCurrentProject();
        }

        public static Properties getCurrentMavenProperties() {
            return Dew.mavenSession.getUserProperties();
        }


        public static Map<String, FinalProjectConfig> getProjects() {
            return config.getProjects();
        }

    }

    public static class Invoke {

        public static void invoke(String groupId, String artifactId, String version, String goal, Map<String, String> configuration) throws MojoExecutionException {
            log.debug("invoke groupId = " + groupId + " ,artifactId = " + artifactId + " ,version = " + version);
            List<Element> config = configuration.entrySet().stream()
                    .map(item -> element(item.getKey(), item.getValue()))
                    .collect(Collectors.toList());
            org.apache.maven.model.Plugin plugin;
            if (version == null) {
                plugin = plugin(groupId, artifactId);
            } else {
                plugin = plugin(groupId, artifactId, version);
            }
            MojoExecutor.executeMojo(
                    plugin,
                    goal(goal),
                    configuration(config.toArray(new Element[]{})),
                    executionEnvironment(
                            mavenSession.getCurrentProject(),
                            mavenSession,
                            mavenPluginManager
                    )
            );
        }

    }

    public static class Notify {

        public static void success(String content, String mojoName) {
            send(null, content, mojoName);
        }

        public static void fail(Throwable content, String mojoName) {
            send(content, null, mojoName);
        }

        private static void send(Throwable failContent, String successContent, String mojoName) {
            if (mojoName.equalsIgnoreCase("log")) {
                return;
            }
            String flag = Dew.Config.getCurrentProject().getId();
            if (ms.dew.notification.Notify.contains(flag)) {
                String title = Dew.Config.getCurrentProject().getProfile()
                        + " "
                        + Dew.Config.getCurrentProject().getAppName()
                        + " "
                        + mojoName;
                if (failContent != null) {
                    ms.dew.notification.Notify.send(flag, failContent, title);
                } else {
                    ms.dew.notification.Notify.send(flag, successContent, title);
                }
            }
        }

    }

    public static class Mock {

        public static void loadClass(String classPath) throws NoSuchMethodException, MalformedURLException, InvocationTargetException, IllegalAccessException, ClassNotFoundException {
            File clazzPath = new File(classPath);
            if (!clazzPath.exists() || clazzPath.isFile()) {
                log.debug("Not found mock class path in " + classPath);
                return;
            }
            Optional<File> mockFiles = Stream.of(clazzPath.listFiles()).filter(f -> f.getName().equals("mock") && f.isDirectory()).findAny();
            if (!mockFiles.isPresent()) {
                log.debug("Mock class path must contain a directory named 'mock'");
                return;
            }
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            boolean accessible = method.isAccessible();
            try {
                if (!accessible) {
                    method.setAccessible(true);
                }
                URLClassLoader classLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
                method.invoke(classLoader, clazzPath.toURI().toURL());
            } finally {
                method.setAccessible(accessible);
            }
            File[] classFiles = mockFiles.get().listFiles(pathname -> pathname.getName().endsWith(".class"));
            for (File file : classFiles) {
                log.debug("Loading class " + file.getName());
                Class.forName("mock." + file.getName().split("\\.")[0]);
            }
        }

        public static void invokeMock() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
            Class.forName("mock.Mock").newInstance();
        }
    }

    public static class Utils {

        /**
         * 注意，此方法调用时未必执行了对应项目的phase,所以有些参数拿不到.
         *
         * @param mavenProject
         * @return
         */
        public static AppKind checkAppKind(MavenProject mavenProject) {
            AppKind appKind = null;
            if (mavenProject.getPackaging().equalsIgnoreCase("maven-plugin")) {
                // 排除 插件类型
            } else if (new File(mavenProject.getBasedir().getPath() + File.separator + "package.json").exists()) {
                appKind = AppKind.FRONTEND;
            } else if (mavenProject.getPackaging().equalsIgnoreCase("jar")
                    && new File(mavenProject.getBasedir().getPath() + File.separator + "src" + File.separator + "main" + File.separator + "resources").exists()
                    && Arrays.stream(Objects.requireNonNull(new File(mavenProject.getBasedir().getPath() + File.separator + "src" + File.separator + "main" + File.separator + "resources").listFiles()))
                    .anyMatch((res -> res.getName().toLowerCase().contains("application")
                            || res.getName().toLowerCase().contains("bootstrap")))
                    // 包含DependencyManagement内容，不精确
                    && mavenProject.getManagedVersionMap().containsKey("org.springframework.boot:spring-boot-starter-web:jar")
                // TODO 以下判断无效
                /* mavenProject.getResources() != null && mavenProject.getResources().stream()
                    .filter(res -> res.getDirectory().contains("src\\main\\resources")
                            && res.getIncludes() != null)
                    .anyMatch(res -> res.getIncludes().stream()
                            .anyMatch(file -> file.toLowerCase().contains("application")
                                    || file.toLowerCase().contains("bootstrap")))
                   && mavenProject.getArtifacts()
                    .stream().anyMatch(artifact ->
                    artifact.getScope().equals(Artifact.SCOPE_RUNTIME)
                            && artifact.getArtifactId().equalsIgnoreCase("spring-boot-starter-web")*/
            ) {
                appKind = AppKind.JVM_SERVICE;
            } else if (mavenProject.getPackaging().equalsIgnoreCase("jar")) {
                appKind = AppKind.JVM_LIB;
            }else if (mavenProject.getPackaging().equalsIgnoreCase("pom")) {
                appKind = AppKind.POM;
            }

            Dew.log.debug("Current app [" + mavenProject.getArtifactId() + "] kind is " + appKind);
            return appKind;
        }

    }


}
