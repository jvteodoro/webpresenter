package webpresenter.server.library;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by modules that reference library media. Deleting a file that is still in use is
 * refused, so an event never silently loses one of its items.
 */
public interface MediaUsage {

    /** Human-readable descriptions of where the media is used (empty if unused). */
    List<String> usagesOf(UUID mediaId);
}
