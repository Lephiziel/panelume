package dev.panelume.reader;

import org.json.JSONObject;

/** Explicit allowlist of portable preferences; invalid fields never affect library recovery. */
final class BackupSettings {
    static final int LANGUAGE=1, NETWORK=2, SEARCH=4;
    final String language;
    final Boolean unmetered;
    final String lastSearch;

    private BackupSettings(String language, Boolean unmetered, String lastSearch) {
        this.language=language; this.unmetered=unmetered; this.lastSearch=lastSearch;
    }

    static BackupSettings parse(JSONObject object) {
        if (object == null) return new BackupSettings(null,null,null);
        Object l=object.opt("language"), n=object.opt("unmetered"), q=object.opt("last_search");
        String language=("en".equals(l)||"ru".equals(l)) ? (String)l : null;
        Boolean network=n instanceof Boolean ? (Boolean)n : null;
        String search=q instanceof String ? (String)q : null;
        // Bound display/input size and reject control and bidi formatting characters.
        if (search!=null && (search.length()>2048 || search.matches("(?s).*[\\p{Cc}\\p{Cf}].*"))) search=null;
        return new BackupSettings(language,network,search);
    }

    int available() {
        return (language!=null?LANGUAGE:0)|(unmetered!=null?NETWORK:0)|(lastSearch!=null?SEARCH:0);
    }
}
