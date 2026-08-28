package ai.luna.builtin;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/** Files, in whatever storage provider the environment offers. */
public final class FilesystemTools extends BuiltinProvider {

    public FilesystemTools() {
        super(declare());
    }

    @Override
    public String id() {
        return "core.filesystem";
    }

    private static List<ToolDefinition> declare() {
        List<ToolDefinition> all = new ArrayList<>();
        all.add(ToolDefinition.of("list_files", "List files")
            .description("What is in a folder")
            .input("path", "Folder, relative to the granted one. Empty means the root")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("read_file", "Read a file")
            .description("The text of one file")
            .input("path", "File, relative to the granted folder")
            .required("path")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("search_code", "Search")
            .description("Find text anywhere under the granted folder")
            .input("query", "What to look for")
            .required("query")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("write_file", "Write a file")
            .description("Replace a file's contents. The old bytes are kept for undo")
            .input("path", "File to write", "content", "What to write")
            .required("path", "content")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("create_file", "Create a file")
            .description("Make an empty file")
            .input("path", "File to create")
            .required("path")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("create_folder", "Create a folder")
            .description("Make a folder")
            .input("path", "Folder to create")
            .required("path")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("delete_file", "Delete")
            .description("Remove a file. A backup is kept")
            .input("path", "File to delete")
            .required("path")
            .capabilities(Capability.FILESYSTEM_DELETE)
            .risk(RiskLevel.HIGH)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("rename_file", "Rename")
            .description("Give a file a different name")
            .input("path", "File to rename", "newName", "The new name")
            .required("path", "newName")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());
        return all;
    }
}
