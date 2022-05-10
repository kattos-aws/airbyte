/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.helper;

import io.airbyte.config.AttemptFailureSummary;
import io.airbyte.config.FailureReason;
import io.airbyte.config.FailureReason.FailureOrigin;
import io.airbyte.config.FailureReason.FailureType;
import io.airbyte.config.Metadata;
import io.airbyte.protocol.models.AirbyteTraceMessage;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class FailureHelper {

  private static final String JOB_ID_METADATA_KEY = "jobId";
  private static final String ATTEMPT_NUMBER_METADATA_KEY = "attemptNumber";
  private static final String TRACE_MESSAGE_METADATA_KEY = "from_trace_message";

  private static final String WORKFLOW_TYPE_SYNC = "SyncWorkflow";
  private static final String ACTIVITY_TYPE_REPLICATE = "Replicate";
  private static final String ACTIVITY_TYPE_PERSIST = "Persist";
  private static final String ACTIVITY_TYPE_NORMALIZE = "Normalize";
  private static final String ACTIVITY_TYPE_DBT_RUN = "Run";

  public static FailureReason genericFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return new FailureReason()
        .withInternalMessage(t.getMessage())
        .withStacktrace(ExceptionUtils.getStackTrace(t))
        .withTimestamp(System.currentTimeMillis())
        .withMetadata(jobAndAttemptMetadata(jobId, attemptNumber));
  }

  public static FailureReason genericFailure(final AirbyteTraceMessage m, final Long jobId, final Integer attemptNumber) {
    return new FailureReason()
        .withInternalMessage(m.getError().getInternalMessage())
        .withStacktrace(m.getError().getStackTrace())
        .withTimestamp(m.getEmittedAt().longValue())
        .withMetadata(traceMessageMetadata(jobId, attemptNumber));
  }

  public static FailureReason sourceFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.SOURCE)
        .withExternalMessage("Something went wrong within the source connector");
  }

  public static FailureReason sourceFailure(final AirbyteTraceMessage m, final Long jobId, final Integer attemptNumber) {
    return genericFailure(m, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.SOURCE)
        .withExternalMessage(m.getError().getMessage());
  }

  public static FailureReason destinationFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.DESTINATION)
        .withExternalMessage("Something went wrong within the destination connector");
  }

  public static FailureReason destinationFailure(final AirbyteTraceMessage m, final Long jobId, final Integer attemptNumber) {
    return genericFailure(m, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.DESTINATION)
        .withExternalMessage(m.getError().getMessage());
  }

  public static FailureReason errorTraceMessageFailure(final AirbyteTraceMessage sourceMessage,
                                                       final AirbyteTraceMessage destinationMessage,
                                                       final Long jobId,
                                                       final Integer attempt) {
    if (sourceMessage == null && destinationMessage == null) {
      return null;
    }

    if (destinationMessage == null) {
      return sourceFailure(sourceMessage, jobId, attempt);
    }

    if (sourceMessage == null) {
      return destinationFailure(destinationMessage, jobId, attempt);
    }

    if (sourceMessage.getEmittedAt() < destinationMessage.getEmittedAt()) {
      return sourceFailure(sourceMessage, jobId, attempt);
    } else {
      return destinationFailure(destinationMessage, jobId, attempt);
    }

  }

  public static FailureReason replicationFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.REPLICATION)
        .withExternalMessage("Something went wrong during replication");
  }

  public static FailureReason persistenceFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.PERSISTENCE)
        .withExternalMessage("Something went wrong during state persistence");
  }

  public static FailureReason normalizationFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.NORMALIZATION)
        .withExternalMessage("Something went wrong during normalization");
  }

  public static FailureReason dbtFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withFailureOrigin(FailureOrigin.DBT)
        .withExternalMessage("Something went wrong during dbt");
  }

  public static FailureReason unknownOriginFailure(final Throwable t, final Long jobId, final Integer attemptNumber) {
    return genericFailure(t, jobId, attemptNumber)
        .withExternalMessage("An unknown failure occurred");
  }

  public static AttemptFailureSummary failureSummary(final Set<FailureReason> failures, final Boolean partialSuccess) {
    return new AttemptFailureSummary()
        .withFailures(orderedFailures(failures))
        .withPartialSuccess(partialSuccess);
  }

  public static AttemptFailureSummary failureSummaryForCancellation(final Long jobId,
                                                                    final Integer attemptNumber,
                                                                    final Set<FailureReason> failures,
                                                                    final Boolean partialSuccess) {
    failures.add(new FailureReason()
        .withFailureType(FailureType.MANUAL_CANCELLATION)
        .withInternalMessage("Setting attempt to FAILED because the job was cancelled")
        .withExternalMessage("This attempt was cancelled")
        .withTimestamp(System.currentTimeMillis())
        .withMetadata(jobAndAttemptMetadata(jobId, attemptNumber)));

    return failureSummary(failures, partialSuccess);
  }

  public static FailureReason failureReasonFromWorkflowAndActivity(
                                                                   final String workflowType,
                                                                   final String activityType,
                                                                   final Throwable t,
                                                                   final Long jobId,
                                                                   final Integer attemptNumber) {
    if (workflowType.equals(WORKFLOW_TYPE_SYNC) && activityType.equals(ACTIVITY_TYPE_REPLICATE)) {
      return replicationFailure(t, jobId, attemptNumber);
    } else if (workflowType.equals(WORKFLOW_TYPE_SYNC) && activityType.equals(ACTIVITY_TYPE_PERSIST)) {
      return persistenceFailure(t, jobId, attemptNumber);
    } else if (workflowType.equals(WORKFLOW_TYPE_SYNC) && activityType.equals(ACTIVITY_TYPE_NORMALIZE)) {
      return normalizationFailure(t, jobId, attemptNumber);
    } else if (workflowType.equals(WORKFLOW_TYPE_SYNC) && activityType.equals(ACTIVITY_TYPE_DBT_RUN)) {
      return dbtFailure(t, jobId, attemptNumber);
    } else {
      return unknownOriginFailure(t, jobId, attemptNumber);
    }
  }

  private static Metadata jobAndAttemptMetadata(final Long jobId, final Integer attemptNumber) {
    return new Metadata()
        .withAdditionalProperty(JOB_ID_METADATA_KEY, jobId)
        .withAdditionalProperty(ATTEMPT_NUMBER_METADATA_KEY, attemptNumber);
  }

  private static Metadata traceMessageMetadata(final Long jobId, final Integer attemptNumber) {
    return new Metadata()
        .withAdditionalProperty(JOB_ID_METADATA_KEY, jobId)
        .withAdditionalProperty(ATTEMPT_NUMBER_METADATA_KEY, attemptNumber)
        .withAdditionalProperty(TRACE_MESSAGE_METADATA_KEY, true);
  }

  /**
   * Orders failures by putting errors from trace messages first, and then orders by timestamp, so
   * that earlier failures come first.
   */
  public static List<FailureReason> orderedFailures(final Set<FailureReason> failures) {
    final Comparator<FailureReason> compareByIsTrace = Comparator.comparing(failureReason -> {
      final Object metadata = failureReason.getMetadata();
      if (metadata != null) {
        return failureReason.getMetadata().getAdditionalProperties().containsKey(TRACE_MESSAGE_METADATA_KEY) ? 0 : 1;
      } else {
        return 1;
      }
    });
    final Comparator<FailureReason> compareByTimestamp = Comparator.comparing(FailureReason::getTimestamp);
    final Comparator<FailureReason> compareByTraceAndTimestamp = compareByIsTrace.thenComparing(compareByTimestamp);
    return failures.stream().sorted(compareByTraceAndTimestamp).toList();
  }

}
