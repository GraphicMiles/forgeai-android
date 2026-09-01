package ai.luna.builtin;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/** The web, through whichever browser provider the environment offers. */
public final class BrowserTools extends BuiltinProvider {

    public BrowserTools() {
        super(declare());
    }

    @Override
    public String id() {
        return "core.browser";
    }

    private static List<ToolDefinition> declare() {
        List<ToolDefinition> all = new ArrayList<>();
        all.add(ToolDefinition.of("open_page", "Open a page")
            .description("Load a web page in the windowless browser")
            .input("url", "The address. Never invent one")
            .required("url")
            .capabilities(Capability.BROWSER_NAVIGATE, Capability.NETWORK_REQUEST)
            .risk(RiskLevel.MEDIUM)
            .timeout(20000L)
            .requires("browser")
            .build());
        all.add(ToolDefinition.of("read_page", "Read the page")
            .description("The text of the page that is open")
            .capabilities(Capability.BROWSER_READ)
            .risk(RiskLevel.MEDIUM)
            .requires("browser")
            .build());
        all.add(ToolDefinition.of("search_web", "Search the web")
            .description("Search the web for a query and return the top results with snippets")
            .input("query", "What to search for")
            .required("query")
            .capabilities(Capability.BROWSER_NAVIGATE, Capability.BROWSER_READ,
                Capability.NETWORK_REQUEST)
            .risk(RiskLevel.MEDIUM)
            .timeout(20000L)
            .requires("browser")
            .build());
        return all;
    }
}
