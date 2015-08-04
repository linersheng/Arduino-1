/*
 * This file is part of Arduino.
 *
 * Copyright 2014 Arduino LLC (http://www.arduino.cc/)
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * As a special exception, you may use this file as part of a free software
 * library without restriction.  Specifically, if other files instantiate
 * templates or use macros or inline functions from this file, or you compile
 * this file and link it with other files to produce an executable, this
 * file does not by itself cause the resulting executable to be covered by
 * the GNU General Public License.  This exception does not however
 * invalidate any other reasons why the executable file might be covered by
 * the GNU General Public License.
 */

package cc.arduino.contributions.packages;

import cc.arduino.Constants;
import cc.arduino.contributions.DownloadableContribution;
import cc.arduino.contributions.DownloadableContributionsDownloader;
import cc.arduino.contributions.SignatureVerifier;
import cc.arduino.filters.FileExecutablePredicate;
import cc.arduino.utils.ArchiveExtractor;
import cc.arduino.utils.MultiStepProgress;
import cc.arduino.utils.Progress;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import processing.app.I18n;
import processing.app.Platform;
import processing.app.PreferencesData;
import processing.app.helpers.FileUtils;
import processing.app.helpers.filefilters.OnlyDirs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

import static processing.app.I18n.format;
import static processing.app.I18n.tr;

public class ContributionInstaller {

  private final ContributionsIndexer indexer;
  private final DownloadableContributionsDownloader downloader;
  private final Platform platform;
  private final SignatureVerifier signatureVerifier;

  public ContributionInstaller(ContributionsIndexer contributionsIndexer, Platform platform, SignatureVerifier signatureVerifier) {
    this.platform = platform;
    this.signatureVerifier = signatureVerifier;
    File stagingFolder = contributionsIndexer.getStagingFolder();
    indexer = contributionsIndexer;
    downloader = new DownloadableContributionsDownloader(stagingFolder) {
      @Override
      protected void onProgress(Progress progress) {
        ContributionInstaller.this.onProgress(progress);
      }
    };
  }

  public List<String> install(ContributedPlatform contributedPlatform) throws Exception {
    List<String> errors = new LinkedList<>();
    if (contributedPlatform.isInstalled()) {
      throw new Exception("Platform is already installed!");
    }

    // Do not download already installed tools
    List<ContributedTool> tools = new LinkedList<>(contributedPlatform.getResolvedTools());
    Iterator<ContributedTool> toolsIterator = tools.iterator();
    while (toolsIterator.hasNext()) {
      ContributedTool tool = toolsIterator.next();
      DownloadableContribution downloadable = tool.getDownloadableContribution(platform);
      if (downloadable == null) {
        throw new Exception(format(tr("Tool {0} is not available for your operating system."), tool.getName()));
      }
      if (downloadable.isInstalled()) {
        toolsIterator.remove();
      }
    }

    // Calculate progress increases
    MultiStepProgress progress = new MultiStepProgress((tools.size() + 1) * 2);

    // Download all
    try {
      // Download platform
      downloader.download(contributedPlatform, progress, tr("Downloading boards definitions."));
      progress.stepDone();

      // Download tools
      int i = 1;
      for (ContributedTool tool : tools) {
        String msg = format(tr("Downloading tools ({0}/{1})."), i, tools.size());
        i++;
        downloader.download(tool.getDownloadableContribution(platform), progress, msg);
        progress.stepDone();
      }
    } catch (InterruptedException e) {
      // Download interrupted... just exit
      return errors;
    }

    ContributedPackage pack = contributedPlatform.getParentPackage();
    File packageFolder = new File(indexer.getPackagesFolder(), pack.getName());

    // TODO: Extract to temporary folders and move to the final destination only
    // once everything is successfully unpacked. If the operation fails remove
    // all the temporary folders and abort installation.

    // Unzip tools on the correct location
    File toolsFolder = new File(packageFolder, "tools");
    int i = 1;
    for (ContributedTool tool : tools) {
      progress.setStatus(format(tr("Installing tools ({0}/{1})..."), i, tools.size()));
      onProgress(progress);
      i++;
      DownloadableContribution toolContrib = tool.getDownloadableContribution(platform);
      File destFolder = new File(toolsFolder, tool.getName() + File.separator + tool.getVersion());

      Files.createDirectories(destFolder.toPath());
      assert toolContrib.getDownloadedFile() != null;
      new ArchiveExtractor(platform).extract(toolContrib.getDownloadedFile(), destFolder, 1);
      try {
        findAndExecutePostInstallScriptIfAny(destFolder, contributedPlatform.getParentPackage().isTrusted(), PreferencesData.getBoolean(Constants.PREF_CONTRIBUTIONS_TRUST_ALL));
      } catch (IOException e) {
        errors.add(tr("Error running post install script"));
      }
      toolContrib.setInstalled(true);
      toolContrib.setInstalledFolder(destFolder);
      progress.stepDone();
    }

    // Unpack platform on the correct location
    progress.setStatus(tr("Installing boards..."));
    onProgress(progress);
    File platformFolder = new File(packageFolder, "hardware" + File.separator + contributedPlatform.getArchitecture());
    File destFolder = new File(platformFolder, contributedPlatform.getParsedVersion());
    Files.createDirectories(destFolder.toPath());
    new ArchiveExtractor(platform).extract(contributedPlatform.getDownloadedFile(), destFolder, 1);
    contributedPlatform.setInstalled(true);
    contributedPlatform.setInstalledFolder(destFolder);
    try {
      findAndExecutePostInstallScriptIfAny(destFolder, contributedPlatform.getParentPackage().isTrusted(), PreferencesData.getBoolean(Constants.PREF_CONTRIBUTIONS_TRUST_ALL));
    } catch (IOException e) {
      e.printStackTrace();
      errors.add(tr("Error running post install script"));
    }
    progress.stepDone();

    progress.setStatus(tr("Installation completed!"));
    onProgress(progress);

    return errors;
  }

  private void findAndExecutePostInstallScriptIfAny(File folder, boolean trusted, boolean trustAll) throws IOException {
    Collection<File> scripts = platform.postInstallScripts(folder).stream().filter(new FileExecutablePredicate()).collect(Collectors.toList());

    if (scripts.isEmpty()) {
      String[] subfolders = folder.list(new OnlyDirs());
      if (subfolders.length != 1) {
        return;
      }

      findAndExecutePostInstallScriptIfAny(new File(folder, subfolders[0]), trusted, trustAll);
      return;
    }

    executeScripts(folder, scripts, trusted, trustAll);
  }

  private void findAndExecutePreUninstallScriptIfAny(File folder, boolean trusted, boolean trustAll) throws IOException {
    Collection<File> scripts = platform.preUninstallScripts(folder).stream().filter(new FileExecutablePredicate()).collect(Collectors.toList());

    if (scripts.isEmpty()) {
      String[] subfolders = folder.list(new OnlyDirs());
      if (subfolders.length != 1) {
        return;
      }

      findAndExecutePreUninstallScriptIfAny(new File(folder, subfolders[0]), trusted, trustAll);
      return;
    }

    executeScripts(folder, scripts, trusted, trustAll);
  }

  private void executeScripts(File folder, Collection<File> postInstallScripts, boolean trusted, boolean trustAll) throws IOException {
    File script = postInstallScripts.iterator().next();

    if (!trusted && !trustAll) {
      System.err.println(I18n.format(tr("Warning: non trusted contribution, skipping script execution ({0})"), script));
      return;
    }

    if (trustAll) {
      System.err.println(I18n.format(tr("Warning: forced untrusted script execution ({0})"), script));
    }

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    Executor executor = new DefaultExecutor();
    executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
    executor.setWorkingDirectory(folder);
    executor.setExitValues(null);
    int exitValue = executor.execute(new CommandLine(script));
    executor.setExitValues(new int[0]);

    System.out.write(stdout.toByteArray());
    System.err.write(stderr.toByteArray());

    if (executor.isFailure(exitValue)) {
      throw new IOException();
    }
  }

  public List<String> remove(ContributedPlatform contributedPlatform) {
    if (contributedPlatform == null || contributedPlatform.isReadOnly()) {
      return new LinkedList<>();
    }
    List<String> errors = new LinkedList<>();
    try {
      findAndExecutePreUninstallScriptIfAny(contributedPlatform.getInstalledFolder(), contributedPlatform.getParentPackage().isTrusted(), PreferencesData.getBoolean(Constants.PREF_CONTRIBUTIONS_TRUST_ALL));
    } catch (IOException e) {
      errors.add(tr("Error running post install script"));
    }

    FileUtils.recursiveDelete(contributedPlatform.getInstalledFolder());
    contributedPlatform.setInstalled(false);
    contributedPlatform.setInstalledFolder(null);

    // Check if the tools are no longer needed
    for (ContributedTool tool : contributedPlatform.getResolvedTools()) {
      if (indexer.isContributedToolUsed(tool)) {
        continue;
      }

      DownloadableContribution toolContrib = tool.getDownloadableContribution(platform);
      File destFolder = toolContrib.getInstalledFolder();
      FileUtils.recursiveDelete(destFolder);
      toolContrib.setInstalled(false);
      toolContrib.setInstalledFolder(null);

      // We removed the version folder (.../tools/TOOL_NAME/VERSION)
      // now try to remove the containing TOOL_NAME folder
      // (and silently fail if another version of the tool is installed)
      try {
        Files.delete(destFolder.getParentFile().toPath());
      } catch (Exception e) {
        // ignore
      }
    }

    return errors;
  }

  public List<String> updateIndex() throws Exception {
    MultiStepProgress progress = new MultiStepProgress(1);

    List<String> downloadedPackageIndexFilesAccumulator = new LinkedList<>();
    downloadIndexAndSignature(progress, downloadedPackageIndexFilesAccumulator, Constants.PACKAGE_INDEX_URL);

    Set<String> packageIndexURLs = new HashSet<>();
    String additionalURLs = PreferencesData.get(Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, "");
    if (!"".equals(additionalURLs)) {
      packageIndexURLs.addAll(Arrays.asList(additionalURLs.split(",")));
    }

    for (String packageIndexURL : packageIndexURLs) {
      downloadIndexAndSignature(progress, downloadedPackageIndexFilesAccumulator, packageIndexURL);
    }

    progress.stepDone();

    return downloadedPackageIndexFilesAccumulator;
  }

  private void downloadIndexAndSignature(MultiStepProgress progress, List<String> downloadedPackagedIndexFilesAccumulator, String packageIndexUrl) throws Exception {
    File packageIndex = download(progress, packageIndexUrl);
    downloadedPackagedIndexFilesAccumulator.add(packageIndex.getName());
    try {
      File packageIndexSignature = download(progress, packageIndexUrl + ".sig");
      boolean signatureVerified = signatureVerifier.isSigned(packageIndex);
      if (signatureVerified) {
        downloadedPackagedIndexFilesAccumulator.add(packageIndexSignature.getName());
      } else {
        downloadedPackagedIndexFilesAccumulator.remove(packageIndex.getName());
        Files.delete(packageIndex.toPath());
        Files.delete(packageIndexSignature.toPath());
        System.err.println(I18n.format(tr("{0} file signature verification failed. File ignored."), packageIndexUrl));
      }
    } catch (Exception e) {
      //ignore errors
    }
  }

  private File download(MultiStepProgress progress, String packageIndexUrl) throws Exception {
    String statusText = tr("Downloading platforms index...");
    URL url = new URL(packageIndexUrl);
    String[] urlPathParts = url.getFile().split("/");
    File outputFile = indexer.getIndexFile(urlPathParts[urlPathParts.length - 1]);
    File tmpFile = new File(outputFile.getAbsolutePath() + ".tmp");
    downloader.download(url, tmpFile, progress, statusText);

    Files.deleteIfExists(outputFile.toPath());
    Files.move(tmpFile.toPath(), outputFile.toPath());

    return outputFile;
  }

  protected void onProgress(Progress progress) {
    // Empty
  }

  public void deleteUnknownFiles(List<String> downloadedPackageIndexFiles) throws IOException {
    File preferencesFolder = indexer.getIndexFile(".").getParentFile();
    File[] additionalPackageIndexFiles = preferencesFolder.listFiles(new PackageIndexFilenameFilter(Constants.DEFAULT_INDEX_FILE_NAME));
    if (additionalPackageIndexFiles == null) {
      return;
    }
    for (File additionalPackageIndexFile : additionalPackageIndexFiles) {
      if (!downloadedPackageIndexFiles.contains(additionalPackageIndexFile.getName())) {
        Files.delete(additionalPackageIndexFile.toPath());
      }
    }
  }
}
