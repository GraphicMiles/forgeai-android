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
            .description("Load a web page in the windowless browser. This only loads it -- it "
                + "returns the address and title, not the text. Call read_page next to "
                + "actually read it. Use an address a search returned or the person gave you")
            .input("url", "The full address including https://. Never invent one: if you do "
                + "not have a real address, search for it first")
            .required("url")
            .capabilities(Capability.BROWSER_NAVIGATE, Capability.NETWORK_REQUEST)
            .risk(RiskLevel.MEDIUM)
            .timeout(20000L)
            .requires("browser")
            .build());
        all.add(ToolDefinition.of("read_page", "Read the page")
            .description("The text of the page open_page last loaded. Call open_page first -- "
                + "on its own this reads nothing. This is how you actually see a page's "
                + "contents; never describe a page you have only opened")
            .capabilities(Capability.BROWSER_READ)
            .risk(RiskLevel.MEDIUM)
            .requires("browser")
            .build());
        all.add(ToolDefinition.of("search_web", "Search the web")
            .description("Search the web and return the top results with their snippets. Start "
                + "here when you do not have an address. The snippets are often enough to "
                + "answer with; open_page only if you need more than a snippet gives. If it "
                + "returns nothing, say so -- never write out plausible-looking results")
            .input("query", "What to search for, in a few words. Do not add the current year "
                + "unless the person asked about a specific year")
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
