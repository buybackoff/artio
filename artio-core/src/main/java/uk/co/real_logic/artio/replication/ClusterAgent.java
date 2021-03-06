/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.replication;

import io.aeron.ExclusivePublication;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.Agent;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.engine.logger.ArchiveReader;
import uk.co.real_logic.artio.engine.logger.Archiver;

import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static uk.co.real_logic.artio.LogTag.RAFT;
import static uk.co.real_logic.artio.replication.PositionTranslations.transportToReplicated;

/**
 * Agent that manages the clustering and archival.
 */
public class ClusterAgent implements Agent
{
    private static final int FRAGMENT_LIMIT = 5;
    private static final int HEARTBEAT_TO_TIMEOUT_RATIO = 4;

    private final short nodeId;
    private final int ourSessionId;
    private final TermState termState = new TermState();
    private final Leader leader;
    private final Candidate candidate;
    private final Follower follower;
    private final RaftTransport transport;
    private final OutboundPipe outboundPipe;
    private final ClusterStreams clusterStreams;
    private final NodeStateHandler nodeStateHandler;
    private final RoleHandler roleHandler;
    private final String agentNamePrefix;
    private final ArchiveReader agentArchiveReader;
    private final Archiver archiver;
    private final ExclusivePublication dataPublication;
    private final RaftArchiver raftArchiver;

    private Role currentRole;

    public ClusterAgent(final ClusterConfiguration configuration, final long timeInMs)
    {
        configuration.conclude();

        nodeId = configuration.nodeId();
        transport = configuration.raftTransport();
        nodeStateHandler = configuration.nodeStateHandler();
        roleHandler = configuration.nodeHandler();
        agentNamePrefix = configuration.agentNamePrefix();
        final Supplier<ArchiveReader> archiveReaderSupplier = configuration.archiveReaderSupplier();

        requireNonNull(archiveReaderSupplier, "archiveReader");

        agentArchiveReader = archiveReaderSupplier.get();
        archiver = configuration.archiver();
        dataPublication = transport.leaderPublication();
        ourSessionId = dataPublication.sessionId();

        final long timeoutIntervalInMs = configuration.timeoutIntervalInMs();
        final long heartbeatTimeInMs = timeoutIntervalInMs / HEARTBEAT_TO_TIMEOUT_RATIO;
        final IntHashSet otherNodes = configuration.otherNodes();
        final int clusterSize = otherNodes.size() + 1;
        final AcknowledgementStrategy acknowledgementStrategy = configuration.acknowledgementStrategy();
        raftArchiver = new RaftArchiver(termState.leaderSessionId(), archiver);
        final DirectBuffer nodeState = configuration.nodeState();

        requireNonNull(otherNodes, "otherNodes");
        requireNonNull(acknowledgementStrategy, "acknowledgementStrategy");
        requireNonNull(archiver, "archiver");

        leader = new Leader(
            nodeId,
            acknowledgementStrategy,
            otherNodes,
            this,
            timeInMs,
            heartbeatTimeInMs,
            termState,
            ourSessionId,
            agentArchiveReader,
            raftArchiver,
            nodeState,
            nodeStateHandler);

        candidate = new Candidate(
            nodeId,
            ourSessionId,
            this,
            clusterSize,
            timeoutIntervalInMs,
            termState,
            acknowledgementStrategy,
            nodeState,
            nodeStateHandler);

        follower = new Follower(
            nodeId,
            this,
            timeInMs,
            timeoutIntervalInMs,
            termState,
            raftArchiver,
            nodeState,
            nodeStateHandler);

        transport.initialiseRoles(leader, candidate, follower);

        startAsFollower(timeInMs);

        clusterStreams = new ClusterStreams(
            transport, ourSessionId, termState, dataPublication, archiveReaderSupplier);
        outboundPipe = new OutboundPipe(configuration.copyToPublication(), clusterStreams());
    }

    public long archivedPosition()
    {
        return transportToReplicated(raftArchiver.archivedTransportPosition(), termState.transportPositionDelta());
    }

    private abstract class NodeState
    {
        void transitionToLeader(final Candidate candidate, final long timeInMs)
        {
            throw new UnsupportedOperationException();
        }

        void transitionToCandidate(final Follower follower, final long timeInMs)
        {
            throw new UnsupportedOperationException();
        }

        void transitionToFollower(final Candidate candidate, final short votedFor, final long timeInMs)
        {
            throw new UnsupportedOperationException();
        }

        void transitionToFollower(final Leader leader, final short votedFor, final long timeInMs)
        {
            throw new UnsupportedOperationException();
        }

        void transitionToCandidate(final Candidate candidate, final long timeInMs)
        {
            throw new UnsupportedOperationException();
        }
    }

    private final NodeState leaderState = new NodeState()
    {
        void transitionToFollower(final Leader leader, final short votedFor, final long timeInMs)
        {
            final int leadershipTerm = termState.leadershipTerm();
            DebugLogger.log(RAFT, "%d: L -> Follower @ %d in %d%n", nodeId, timeInMs, leadershipTerm);

            leader.closeStreams();

            transport.injectFollowerSubscriptions(follower);

            currentRole = follower
                .votedFor(votedFor)
                .follow(timeInMs);

            onNewLeader();
            roleHandler.onTransitionToFollower(leadershipTerm);
        }
    };

    private final NodeState followerState = new NodeState()
    {
        void transitionToCandidate(final Follower follower, final long timeInMs)
        {
            final int leadershipTerm = termState.leadershipTerm();
            DebugLogger.log(RAFT, "%d: F -> Candidate @ %d in %d%n", nodeId, timeInMs, leadershipTerm);

            follower.closeStreams();

            currentRole = candidate.startNewElection(timeInMs);
            onNoLeader();
            roleHandler.onTransitionToCandidate(leadershipTerm);
        }
    };

    private final NodeState candidateState = new NodeState()
    {
        void transitionToLeader(final Candidate candidate, final long timeInMs)
        {
            final int leadershipTerm = termState.leadershipTerm();
            DebugLogger.log(RAFT, "%d: C -> Leader @ %d in %d%n", nodeId, timeInMs, leadershipTerm);

            candidate.closeStreams();

            transport.injectLeaderSubscriptions(leader);

            currentRole = leader.getsElected(timeInMs, dataPublication.position());

            onNewLeader();
            roleHandler.onTransitionToLeader(leadershipTerm);
        }

        void transitionToFollower(final Candidate candidate, final short votedFor, final long timeInMs)
        {
            final int leadershipTerm = termState.leadershipTerm();
            DebugLogger.log(RAFT, "%d: C -> Follower @ %d in %d%n", nodeId, timeInMs, leadershipTerm);

            candidate.closeStreams();

            transport.injectFollowerSubscriptions(follower);

            currentRole = follower.votedFor(votedFor).follow(timeInMs);

            onNoLeader();
            roleHandler.onTransitionToFollower(leadershipTerm);
        }
    };

    private void startAsFollower(final long timeInMs)
    {
        transport.injectFollowerSubscriptions(follower);

        currentRole = follower.follow(timeInMs);

        onNoLeader();
        roleHandler.onTransitionToFollower(termState.leadershipTerm());
    }

    void transitionToFollower(final Candidate candidate, final short votedFor, final long timeInMs)
    {
        candidateState.transitionToFollower(candidate, votedFor, timeInMs);
    }

    void transitionToFollower(final Leader leader, final short votedFor, final long timeInMs)
    {
        leaderState.transitionToFollower(leader, votedFor, timeInMs);
    }

    void transitionToLeader(final long timeInMs)
    {
        candidateState.transitionToLeader(candidate, timeInMs);
    }

    void transitionToCandidate(final long timeInMs)
    {
        followerState.transitionToCandidate(follower, timeInMs);
    }

    void onNewLeader()
    {
        nodeStateHandler.onNewLeader(termState.leaderSessionId().get());
    }

    void onNoLeader()
    {
        nodeStateHandler.noLeader();
    }

    public int doWork()
    {
        final long timeInMs = System.currentTimeMillis();
        final Role role = currentRole;
        final int commandCount = role.pollCommands(FRAGMENT_LIMIT, timeInMs);

        if (role != currentRole)
        {
            return commandCount + doWork();
        }

        return commandCount +
            role.readData() +
            role.checkConditions(timeInMs) +
            outboundPipe.poll(FRAGMENT_LIMIT);
    }

    public boolean isLeader()
    {
        return currentRole == leader;
    }

    public boolean isCandidate()
    {
        return currentRole == candidate;
    }

    public boolean isFollower()
    {
        return currentRole == follower;
    }

    public short nodeId()
    {
        return nodeId;
    }

    public TermState termState()
    {
        return termState;
    }

    public ClusterStreams clusterStreams()
    {
        return clusterStreams;
    }

    public void onClose()
    {
        leader.closeStreams();
        follower.closeStreams();
        candidate.closeStreams();
        archiver.onClose();
        agentArchiveReader.close();
    }

    public String roleName()
    {
        return agentNamePrefix + "Cluster Agent";
    }

    public int ourSessionId()
    {
        return ourSessionId;
    }
}
