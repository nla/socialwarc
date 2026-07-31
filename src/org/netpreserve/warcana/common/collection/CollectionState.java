package org.netpreserve.warcana.common.collection;

import java.util.LinkedHashMap;
import java.util.Map;

public class CollectionState {
    public int version = 1;
    public Map<String, AccountProgress> accounts = new LinkedHashMap<>();
    public String openWarc;
    public String finalWarc;
    public long openCommittedOffset;
    public String mediaOpenWarc;
    public String mediaFinalWarc;
    public long mediaCommittedOffset;
    public String videoOpenWarc;
    public String videoFinalWarc;
    public long videoCommittedOffset;

    public CollectionState() {
    }

    public static class AccountProgress {
        public String platform;
        public String account;
        public String latestIdentity;
        public String updatedAt;

        public AccountProgress() {
        }
    }
}
