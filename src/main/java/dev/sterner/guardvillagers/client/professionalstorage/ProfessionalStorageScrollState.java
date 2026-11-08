package dev.sterner.guardvillagers.client.professionalstorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local-only tab selection and independent vertical offsets for one open screen. */
public final class ProfessionalStorageScrollState {
    private final Map<String, Integer> offsets = new HashMap<>();
    private String selectedTabId;

    public void synchronizeTabs(List<String> orderedTabIds) {
        if (orderedTabIds.isEmpty()) {
            clear();
            return;
        }
        Set<String> validIds = new HashSet<>(orderedTabIds);
        offsets.keySet().retainAll(validIds);
        for (String tabId : orderedTabIds) {
            offsets.putIfAbsent(tabId, 0);
        }
        if (selectedTabId == null || !validIds.contains(selectedTabId)) {
            selectedTabId = orderedTabIds.getFirst();
        }
    }

    public String selectedTabId() {
        return selectedTabId;
    }

    public boolean select(String tabId) {
        if (!offsets.containsKey(tabId)) {
            return false;
        }
        selectedTabId = tabId;
        return true;
    }

    public int offset(String tabId) {
        return offsets.getOrDefault(tabId, 0);
    }

    public void setOffset(String tabId, int offset, int maximum) {
        if (offsets.containsKey(tabId)) {
            offsets.put(tabId, ProfessionalStoragePanelLayout.clampScroll(offset, maximum));
        }
    }

    public void clampOffsets(Map<String, Integer> maximumByTab) {
        maximumByTab.forEach((tabId, maximum) ->
                setOffset(tabId, offset(tabId), maximum));
    }

    public void clear() {
        offsets.clear();
        selectedTabId = null;
    }
}
