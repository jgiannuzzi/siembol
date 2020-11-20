package uk.co.gresearch.siembol.configeditor.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import uk.co.gresearch.siembol.configeditor.common.ConfigInfo;
import uk.co.gresearch.siembol.configeditor.model.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

public class GitRepository implements Closeable {
    private static final String MISSING_ARGUMENTS_MSG = "Missing arguments required for git repository initialisation";
    private static final String ERROR_INIT_MSG = "Error during git repository initialisation";
    public static final String MAIN_BRANCH = "master";
    private static final String GIT_REPO_DIRECTORY_URL_FORMAT = "%s/%s/tree/master/%s";
    private final CredentialsProvider credentialsProvider;
    private final Git git;
    private final String gitUrl;
    private final String repoName;
    private final String repoFolder;
    private final String repoUri;
    private final ConfigEditorFile.ContentType contentType;

    private static String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), UTF_8);
    }

    private GitRepository(Builder builder) {
        credentialsProvider = builder.credentialsProvider;
        git = builder.git;
        repoUri = builder.repoUri;
        contentType = builder.contentType;
        repoFolder = builder.repoFolder;
        gitUrl = builder.gitUrl;
        repoName = builder.repoName;
    }

    public ConfigEditorResult transactCopyAndCommit(
            ConfigInfo configInfo,
            String directory,
            Function<String, Boolean> fileNameFilter) throws GitAPIException, IOException {
        Path currentPath = Paths.get(repoFolder, directory);

        git.pull()
                .setCredentialsProvider(credentialsProvider)
                .call();

        if (!MAIN_BRANCH.equals(configInfo.getBranchName())) {
            git.branchCreate().setName(configInfo.getBranchName()).call();
            git.checkout().setName(configInfo.getBranchName()).call();
        }

        if (configInfo.shouldCleanDirectory()) {
            FileUtils.cleanDirectory(currentPath.toFile());
        }

        for (Map.Entry<String, String> file : configInfo.getFilesContent().entrySet()) {
            Path filePath = Paths.get(currentPath.toString(), file.getKey());
            Files.write(filePath, file.getValue().getBytes());
        }

        git.add()
                .addFilepattern(currentPath.getFileName().toString())
                .call();

        git.commit()
                .setAll(true)
                .setAuthor(configInfo.getCommitter(), configInfo.getCommitterEmail())
                .setMessage(configInfo.getCommitMessage())
                .call();

        git.push()
                .setCredentialsProvider(credentialsProvider)
                .call();

        ConfigEditorResult result = getFiles(directory, fileNameFilter);

        if (!MAIN_BRANCH.equals(configInfo.getBranchName())) {
            git.checkout().setName(MAIN_BRANCH).call();
        }
        return result;
    }


    public ConfigEditorResult getFiles(String directory) throws IOException, GitAPIException {
        return getFiles(directory, x -> true);
    }

    public ConfigEditorResult getFiles(String directory,
                                       Function<String, Boolean> fileNameFilter) throws IOException, GitAPIException {
        Path path = Paths.get(repoFolder, directory);
        git.pull()
                .setCredentialsProvider(credentialsProvider)
                .call();

        Map<String, ConfigEditorFile> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(path)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(x -> fileNameFilter.apply(x.getFileName().toString()))
                    .forEach(x -> {
                        try {
                            files.put(x.getFileName().toString(),
                                    new ConfigEditorFile(x.getFileName().toString(), readFile(x), contentType));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        Iterable<RevCommit> commits = git.log().setRevFilter(RevFilter.NO_MERGES).call();
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(git.getRepository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            for (RevCommit commit : commits) {
                if (commit.getParentCount() == 0) {
                    //NOTE: we skip init commit
                    continue;
                }

                String author = commit.getAuthorIdent().getName();
                int commitTime = commit.getCommitTime();
                RevCommit parent = commit.getParent(0);

                List<DiffEntry> diffs = df.scan(parent.getTree(), commit.getTree());
                for (DiffEntry diff : diffs) {
                    int linesAdded = 0, linesRemoved = 0;
                    int lastSlashIndex = diff.getNewPath().lastIndexOf('/');
                    String fileName = lastSlashIndex < 0
                            ? diff.getNewPath()
                            : diff.getNewPath().substring(lastSlashIndex + 1);
                    if (!files.containsKey(fileName)) {
                        continue;
                    }

                    for (Edit edit : df.toFileHeader(diff).toEditList()) {
                        linesRemoved += edit.getEndA() - edit.getBeginA();
                        linesAdded += edit.getEndB() - edit.getBeginB();
                    }

                    ConfigEditorFileHistoryItem historyItem = new ConfigEditorFileHistoryItem();
                    historyItem.setAuthor(author);
                    historyItem.setTimestamp(commitTime);
                    historyItem.setAddedLines(linesAdded);
                    historyItem.setRemoved(linesRemoved);
                    files.get(fileName).getFileHistory().add(historyItem);
                }
            }
        }

        ConfigEditorAttributes attr = new ConfigEditorAttributes();
        attr.setFiles(files.values().stream().collect(Collectors.toList()));
        return new ConfigEditorResult(ConfigEditorResult.StatusCode.OK, attr);
    }

    public String getRepoUri() {
        return repoUri;
    }

    public String getDirectoryUrl(String directory) {
        return String.format(GIT_REPO_DIRECTORY_URL_FORMAT, gitUrl, repoName, directory);
    }

    @Override
    public void close() {
        git.close();
    }

    public static class Builder {
        private static final String GIT_REPO_URL_FORMAT = "%s/%s.git";
        private String repoName;
        private String repoUri;
        private String gitUrl;
        private String repoFolder;
        private CredentialsProvider credentialsProvider;
        private Git git;
        private ConfigEditorFile.ContentType contentType = ConfigEditorFile.ContentType.RAW_JSON_STRING;

        public Builder repoName(String repoName) {
            this.repoName = repoName;
            return this;
        }

        public Builder gitUrl(String gitUrl) {
            this.gitUrl = gitUrl;
            return this;
        }

        public Builder repoFolder(String repoFolder) {
            this.repoFolder = repoFolder;
            return this;
        }

        public Builder credentials(String userName, String password) {
            credentialsProvider = new UsernamePasswordCredentialsProvider(userName, password);
            return this;
        }

        public GitRepository build() throws GitAPIException, IOException {
            if (repoName == null
                    || gitUrl == null
                    || repoFolder == null
                    || credentialsProvider == null) {
                throw new IllegalArgumentException(MISSING_ARGUMENTS_MSG);
            }

            File repoFolderDir = new File(repoFolder);
            if (repoFolderDir.exists()) {
                FileUtils.cleanDirectory(repoFolderDir);
            } else {
                repoFolderDir.mkdir();
            }

            repoUri = String.format(GIT_REPO_URL_FORMAT, gitUrl, repoName);

            git = Git.cloneRepository()
                    .setCredentialsProvider(credentialsProvider)
                    .setURI(repoUri)
                    .setDirectory(repoFolderDir)
                    .call();

            if (git == null || !repoFolderDir.exists()) {
                throw new IllegalStateException(ERROR_INIT_MSG);
            }

            return new GitRepository(this);
        }
    }
}