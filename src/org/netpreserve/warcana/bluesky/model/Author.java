package org.netpreserve.warcana.bluesky.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.netpreserve.warcana.common.model.Account;

public record Author(
        @NonNull String did,
        @NonNull String handle,
        @Nullable String displayName,
        @Nullable String avatar,
        @Nullable String banner) {

    public Account toGenericAccount() {
        return new Account(handle, displayName, avatar, banner);
    }
}
