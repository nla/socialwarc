package org.netpreserve.socialwarc.common;

import org.netpreserve.jwarc.WarcResponse;

import java.io.IOException;
import java.util.List;

public interface WarcResponseExtractor {
    String platform();

    boolean supports(WarcResponse response);

    List<ExtractedPost> extract(WarcResponse response) throws IOException;
}
