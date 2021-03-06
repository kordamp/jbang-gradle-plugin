/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2020 Andres Almiray.
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
package org.kordamp.gradle.plugin.jbang.tasks

import groovy.transform.CompileStatic
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException
import org.gradle.wrapper.Download
import org.gradle.wrapper.IDownload
import org.gradle.wrapper.Logger
import org.kordamp.gradle.property.DirectoryState
import org.kordamp.gradle.property.ListState
import org.kordamp.gradle.property.SimpleDirectoryState
import org.kordamp.gradle.property.SimpleListState
import org.kordamp.gradle.property.SimpleStringState
import org.kordamp.gradle.property.StringState
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult

import java.nio.file.Path

/**
 * @author Andres Almiray
 */
@CompileStatic
class JBangTask extends DefaultTask {
    private static final boolean IS_OS_WINDOWS = System.getProperty('os.name')
        .toLowerCase(Locale.ENGLISH)
        .contains('windows')

    private static final int OK_EXIT_CODE = 0

    private final StringState script
    private final StringState version
    private final ListState args
    private final ListState trusts
    private final DirectoryState installDir

    JBangTask() {
        DirectoryProperty jbangCacheDirectory = project.objects.directoryProperty()
        jbangCacheDirectory.set(new File(project.gradle.gradleUserHomeDir, 'caches/jbang'))

        script = SimpleStringState.of(this, 'jbang.script', '')
        version = SimpleStringState.of(this, 'jbang.version', '0.48.0')
        args = SimpleListState.of(this, 'jbang.args', [])
        trusts = SimpleListState.of(this, 'jbang.trusts', [])
        installDir = SimpleDirectoryState.of(this, 'jbang.install.dir', jbangCacheDirectory.get())
    }

    // -- Write properties --

    @Internal
    Property<String> getScript() {
        script.property
    }

    @Internal
    Property<String> getVersion() {
        version.property
    }

    @Internal
    ListProperty<String> getArgs() {
        args.property
    }

    @Internal
    ListProperty<String> getTrusts() {
        trusts.property
    }

    @Internal
    DirectoryProperty getInstalldir() {
        installDir.property
    }

    // -- Read-only properties --

    @Input
    Provider<String> getResolvedScript() {
        script.provider
    }

    @Input
    @Optional
    Provider<String> getResolvedVersion() {
        version.provider
    }

    @Input
    @Optional
    Provider<List<String>> getResolvedArgs() {
        args.provider
    }

    @Input
    @Optional
    Provider<List<String>> getResolvedTrusts() {
        trusts.provider
    }

    @Input
    @Optional
    Provider<Directory> getResolvedInstallDir() {
        installDir.provider
    }

    // -- execution --

    @TaskAction
    void runTask() {
        detectJBang()
        executeTrust()
        executeJBang()
    }

    // -- copied from jbang-maven-plugin --

    private Path jbangHome

    private void detectJBang() {
        ProcessResult result = version()
        if (result.getExitValue() == OK_EXIT_CODE) {
            logger.info('Found JBang v.' + result.outputString())
        } else {
            logger.warn('JBang not found. Checking cached version ' + getResolvedVersion().get())

            String jbangVersion = getResolvedVersion().get()
            Path jbangInstallPath = getResolvedInstallDir().get().getAsFile().toPath()
            Path installDir = jbangInstallPath.toAbsolutePath()
            jbangHome = installDir.resolve("jbang-${jbangVersion}".toString())

            result = version()
            if (result.getExitValue() == OK_EXIT_CODE) {
                logger.info('Found JBang v.' + result.outputString())
            } else {
                logger.warn('JBang not found. Downloading version ' + getResolvedVersion().get())
                download()
                result = version()
                if (result.getExitValue() == OK_EXIT_CODE) {
                    logger.info('Using JBang v.' + result.outputString())
                }
            }
        }
    }

    private void download() {
        String jbangVersion = getResolvedVersion().get()
        Path jbangInstallPath = getResolvedInstallDir().get().getAsFile().toPath()

        Path installDir = jbangInstallPath.toAbsolutePath()
        String uri = String.format('https://github.com/jbangdev/jbang/releases/download/v%s/jbang-%s.zip', jbangVersion, jbangVersion)

        Logger logger = new Logger(false)
        IDownload download = new Download(logger, 'jbang', jbangVersion)
        File localZipFile = jbangInstallPath.resolve("jbang-${jbangVersion}.zip".toString()).toFile()
        File tmpZipFile = new File(localZipFile.getParentFile(), localZipFile.getName() + '.part')
        tmpZipFile.delete()
        logger.log('Downloading ' + uri)
        download.download(uri.toURI(), tmpZipFile)
        tmpZipFile.renameTo(localZipFile)

        try {
            unzip(localZipFile, installDir)
        } catch (IOException e) {
            logger.log('Could not unzip ' + localZipFile.getAbsolutePath() + ' to ' + installDir + '.');
            logger.log('Reason: ' + e.getMessage())
            throw e
        }

        jbangHome = installDir.resolve("jbang-${jbangVersion}".toString())

        if (!IS_OS_WINDOWS) {
            jbangHome.resolve('bin').resolve('jbang').toFile().setExecutable(true, false)
        }
    }

    private ProcessResult version() throws BuildException {
        List<String> command = command()
        command.add(findJBangExecutable() + ' version')
        try {
            return new ProcessExecutor()
                .command(command)
                .readOutput(true)
                .destroyOnExit()
                .execute()
        } catch (Exception e) {
            throw new BuildException('Error while fetching the JBang version', e)
        }
    }

    private void executeTrust() {
        if (!getResolvedTrusts().get()) {
            // No trust required
            return
        }
        List<String> command = command()
        command.add(findJBangExecutable() + ' trust add ' + String.join(' ', getResolvedTrusts().get()))
        ProcessResult result = execute(command)
        int exitValue = result.getExitValue()
        if (exitValue != 0 && exitValue != 1) {
            throw new IllegalStateException('Error while trusting JBang URLs. Exit code: ' + result.getExitValue())
        }
    }

    private void executeJBang() {
        List<String> command = command()
        StringBuilder executable = new StringBuilder(findJBangExecutable())
        executable.append(' run ').append(getResolvedScript().get())
        if (getResolvedArgs().get()) {
            executable.append(' ').append(String.join(' ', getResolvedArgs().get()))
        }
        command.add(executable.toString())
        ProcessResult result = execute(command)
        if (result.getExitValue() != 0) {
            throw new IllegalStateException('Error while executing JBang. Exit code: ' + result.getExitValue())
        }
    }

    private ProcessResult execute(List<String> command) throws BuildException {
        logger.info "jbang command = $command"
        try {
            return new ProcessExecutor()
                .command(command)
                .redirectOutput(System.out)
                .redirectError(System.err)
                .destroyOnExit()
                .execute()
        } catch (Exception e) {
            throw new BuildException("Error while executing JBang", e)
        }
    }

    private List<String> command() {
        List<String> command = new ArrayList<>()
        if (IS_OS_WINDOWS) {
            command.add('cmd.exe')
            command.add('/c')
        } else {
            command.add('sh')
            command.add('-c')
        }
        return command
    }

    private String findJBangExecutable() {
        if (jbangHome != null) {
            if (IS_OS_WINDOWS) {
                return jbangHome.resolve('bin/jbang.cmd').toString()
            } else {
                return jbangHome.resolve('bin/jbang').toString()
            }
        } else {
            if (IS_OS_WINDOWS) {
                return 'jbang.cmd'
            } else {
                return 'jbang'
            }
        }
    }

    private void unzip(File zipFile, Path installDir) throws IOException {
        ZipArchiveInputStream archive = null
        try {
            archive = new ZipArchiveInputStream(new BufferedInputStream(new FileInputStream(zipFile)))

            ZipArchiveEntry entry = null
            while ((entry = archive.nextZipEntry) != null) {
                if (entry.directory) continue
                File file = installDir.resolve(entry.name).toFile()
                file.parentFile.mkdirs()
                IOUtils.copy(archive, new FileOutputStream(file))
            }
        } catch (IOException e) {
            archive?.close()
            throw e
        }
    }
}