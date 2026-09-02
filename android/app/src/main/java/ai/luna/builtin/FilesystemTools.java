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
            .description("What is in a folder. Start here when you do not already know what "
                + "exists: it is cheap, and it stops you guessing at a filename. Use it before "
                + "asking the person where something is")
            .input("path", "Folder, relative to the granted one. Empty means the root -- "
                + "which is where a request like \"put it in this folder\" means, so do not "
                + "repeat the granted folder's own name here")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("read_file", "Read a file")
            .description("The text of one file. Read a file before describing it, quoting it, "
                + "or editing it -- never describe contents you have not read")
            .input("path", "File, relative to the granted folder. Use a name you saw in a "
                + "listing rather than one you assume is there")
            .required("path")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("search_code", "Search")
            .description("Find text anywhere under the granted folder, across every file. "
                + "Use this when you know roughly what you are looking for but not which file "
                + "holds it. If you already know the file, read_file is cheaper")
            .input("query", "The text to look for. A short distinctive phrase finds more than "
                + "a whole sentence")
            .required("query")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("write_file", "Write a file")
            .description("Replace a file's whole contents. The old bytes are kept for undo. "
                + "Use this for a new file, or when you genuinely mean to rewrite everything. "
                + "Do not use it to change a few lines of a file that already exists -- that "
                + "means retyping the rest from memory, and anything you misremember is "
                + "destroyed. Use edit_file for that")
            .input("path", "File to write, relative to the granted folder itself — do not "
                + "prefix it with that folder's own name", "content", "What to write")
            .required("path", "content")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("edit_file", "Edit part of a file")
            .description("Replace an exact block of text in a file, leaving the rest alone. "
                + "This is the usual way to change a file that already exists: it cannot "
                + "delete the parts you did not mention. Read the file first and copy the "
                + "find text from what you read. If it reports that the text was not found, "
                + "read the file again and copy more carefully -- do not switch to write_file, "
                + "which would overwrite the whole thing. Several small edits beat one large "
                + "one. Do not use it on a file that does not exist yet")
            .input("path", "File to edit",
                "find", "The exact lines to replace, copied from the file",
                "replace", "What to put in their place",
                "all", "true to change every occurrence")
            .required("path", "find", "replace")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());
        all.add(ToolDefinition.of("create_file", "Create a file")
            .description("Make an empty file. The path it actually lands on is reported back, "
                + "and that reported path is the one to tell the person about. Use this when "
                + "the file should start empty. If you already know what goes in it, use "
                + "write_file instead and save a step")
            .input("path", "File to create, relative to the granted folder itself — do not "
                + "prefix it with that folder's own name. Keep the extension asked for")
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
