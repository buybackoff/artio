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

import io.aeron.logbuffer.ExclusiveBufferClaim;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Timing;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.co.real_logic.artio.LogTag.RAFT;
import static uk.co.real_logic.artio.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.Timing.withTimeout;

/**
 * Test simulated cluster.
 *
 * Every operation should ensure that it has it's own timeout, since we avoid Junit's @Test timeouts
 * as they cause the test code to be run on a different thread.
 */
// TODO: re-enable once time can be allocated to fix this kind of issue
// Bump to Aeron 1.4 exposed a pre-existing race condition.
@Ignore
public class ClusterReplicationTest
{
    private static final int BUFFER_SIZE = 1337;
    private static final int POSITION_AFTER_MESSAGE = BUFFER_SIZE + HEADER_LENGTH;

    private ExclusiveBufferClaim bufferClaim = new ExclusiveBufferClaim();
    private UnsafeBuffer buffer = new UnsafeBuffer(new byte[BUFFER_SIZE]);

    private final NodeRunner node1 = new NodeRunner(1, 2, 3);
    private final NodeRunner node2 = new NodeRunner(2, 1, 3);
    private final NodeRunner node3 = new NodeRunner(3, 1, 2);
    private final NodeRunner[] allNodes = { node1, node2, node3 };

    @Before
    public void hasElectedLeader()
    {
        assertEventuallyFindsLeaderIn(allNodes);

        final NodeRunner leader = leader();
        DebugLogger.log(RAFT, "Leader elected: %d%n%n", leader.clusterAgent().nodeId());
    }

    @After
    public void shutdown()
    {
        for (final NodeRunner nodeRunner : allNodes)
        {
            nodeRunner.close();
        }
    }

    @Test
    public void shouldEstablishCluster()
    {
        checkClusterStable();

        assertNodeStateReplicated();
    }

    @Test
    public void shouldReplicateMessage()
    {
        final NodeRunner leader = leader();

        DebugLogger.log(RAFT, "Leader is %d%n", leader.nodeId());

        final long position = sendMessageTo(leader);

        DebugLogger.log(RAFT, "Leader @ %d%n", position);

        assertMessageReceived(position);
    }

    @Test
    public void shouldReformClusterAfterLeaderPause()
    {
        awaitLeadershipConsensus();

        final NodeRunner leader = leader();
        final NodeRunner[] followers = followers();

        DebugLogger.log(RAFT, "Pausing Leader: %d%n", leader.nodeId());

        assertEventuallyTrue(
            "Failed to find leader",
            () ->
            {
                poll(followers);
                return foundLeader(followers);
            });

        eventuallyOneLeaderAndTwoFollowersWithSameLeader();
    }

    @Test
    public void shouldReformClusterAfterLeaderNetsplit()
    {
        leaderNetSplitScenario(true, true);
    }

    @Test
    public void shouldReformClusterAfterPartialLeaderNetsplit()
    {
        // NB: under other partial failure, the leader would never stop being a leader
        leaderNetSplitScenario(false, true);
    }

    private void leaderNetSplitScenario(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        final NodeRunner leader = leader();
        final NodeRunner[] followers = followers();

        DebugLogger.log(RAFT, "First Leader: %d%n", leader.nodeId());

        final long position = sendMessageTo(leader);

        assertMessageReceived(position);

        DebugLogger.log(RAFT, "Dropping Frames to Leader [%d]%n", leader.nodeId());

        leader.dropFrames(dropInboundFrames, dropOutboundFrames);

        assertElectsNewLeader(followers);

        leader.dropFrames(false);

        assertBecomesFollower(leader);

        final NodeRunner newLeader = leader();

        DebugLogger.log(RAFT, "New Leader: %d%n%s", newLeader.nodeId(), clusterInfo());

        // TODO: enable once this is fixed.
        /*position = sendMessageTo(newLeader);

        assertMessageReceived(position);

        messageCommittedBetweenTwoLiveNodes(leader, position);*/
    }

    @Test
    public void shouldRejoinClusterAfterFollowerNetsplit()
    {
        // NB: under other partial failure, the follower would never stop being a follower
        followerNetSplitScenario(true, true);
    }

    @Test
    public void shouldRejoinClusterAfterPartialFollowerNetsplit()
    {
        followerNetSplitScenario(true, false);
    }

    private void followerNetSplitScenario(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        final NodeRunner follower = aFollower();

        follower.dropFrames(dropInboundFrames, dropOutboundFrames);

        assertBecomesCandidate(follower);

        follower.dropFrames(false);

        eventuallyOneLeaderAndTwoFollowers();
    }

    @Test
    public void shouldReformClusterAfterFollowerNetsplit()
    {
        clusterNetSplitScenario(true, true);
    }

    @Test
    public void shouldReformClusterAfterPartialFollowerNetsplit()
    {
        clusterNetSplitScenario(true, false);
    }

    private void clusterNetSplitScenario(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        final NodeRunner[] followers = followers();

        nodes().forEach((nodeRunner) -> nodeRunner.dropFrames(dropInboundFrames, dropOutboundFrames));

        assertBecomesCandidate(followers);

        nodes().forEach((nodeRunner) -> nodeRunner.dropFrames(false));

        eventuallyOneLeaderAndTwoFollowersWithSameLeader();
    }

    @Ignore
    @Test
    public void shouldReplicateMessageAfterClusterReformed()
    {
        final NodeRunner leader = leader();
        final NodeRunner follower = aFollower();

        follower.dropFrames(true);

        assertBecomesCandidate(follower);

        follower.dropFrames(false);

        eventuallyOneLeaderAndTwoFollowersWithSameLeader();

        final long position = sendMessageTo(leader);

        assertMessageReceived(position);
    }

    @Test
    public void shouldEventuallyReplicateMessageWhenClusterReformed()
    {
        final NodeRunner leader = leader();
        final NodeRunner follower = aFollower();

        follower.dropFrames(true);

        assertBecomesCandidate(follower);

        assertTrue("nodes received message when one was supposedly netsplit",
            noNodesReceivedMessage(POSITION_AFTER_MESSAGE));

        long position = sendMessageTo(leader);

        messageCommittedBetweenTwoLiveNodes(follower, position);

        follower.dropFrames(false);

        eventuallyOneLeaderAndTwoFollowersWithSameLeader();

        assertMessageReceived(position);

        // Replicate another message after net split is over
        position = sendMessageTo(leader);

        assertMessageReceived(position);
    }

    private void messageCommittedBetweenTwoLiveNodes(final NodeRunner notLiveNode, final long position)
    {
        final NodeRunner[] liveNodes = nodes().filter(node -> node != notLiveNode).toArray(NodeRunner[]::new);

        assertEventuallyTrue(
            "message not committed",
            () ->
            {
                pollAll();

                return receivedMessage(liveNodes[0], position) &&
                       receivedMessage(liveNodes[1], position);
            }
        );
    }

    private void assertNodeStateReplicated()
    {
        final int[] nodeIds = { 1, 2, 3 };
        final int followerCount = nodeIds.length - 1;
        final NodeRunner leader = leader();
        final Int2IntHashMap nodeIdToId = leader.nodeIdToId();
        assertEventuallyTrue(
            "Never replicates node state",
            () ->
            {
                pollAll();
                return nodeIdToId.size() >= followerCount;
            });

        final short leaderId = leader.clusterAgent().nodeId();

        for (final int id : nodeIds)
        {
            if (id != leaderId)
            {
                assertEquals(nodeIdToId + " missing " + id, id, nodeIdToId.get(id));
            }
        }
    }

    private NodeRunner aFollower()
    {
        return followers()[0];
    }

    private void assertBecomesCandidate(final NodeRunner... nodes)
    {
        assertBecomes("isCandidate", ClusterAgent::isCandidate, allNodes, nodes);
    }

    private void assertBecomesFollower(final NodeRunner... nodes)
    {
        assertBecomes("isFollower", ClusterAgent::isFollower, allNodes, nodes);
    }

    private void assertBecomes(
        final String message,
        final Predicate<ClusterAgent> predicate,
        final NodeRunner[] toPoll,
        final NodeRunner... nodes)
    {
        final ClusterAgent[] clusterNodes = getRaftNodes(nodes);
        assertEventuallyTrue(
            message + " or (agreement on term) never true",
            () ->
            {
                poll(toPoll);
                return allMatch(clusterNodes, predicate);
            });
    }

    private ClusterAgent[] getRaftNodes(final NodeRunner[] nodes)
    {
        return Stream.of(nodes).map(NodeRunner::clusterAgent).toArray(ClusterAgent[]::new);
    }

    private static <T> boolean allMatch(final T[] values, final Predicate<T> predicate)
    {
        return Stream.of(values).allMatch(predicate);
    }

    private void assertElectsNewLeader(final NodeRunner... followers)
    {
        assertEventuallyFindsLeaderIn(followers);
    }

    private void assertMessageReceived(final long position)
    {
        assertEventuallyTrue(
            "Message not received",
            () ->
            {
                pollAll();
                return !noNodesReceivedMessage(position);
            });

        checkConsistencyOfReplicatedPositions();
    }

    private void checkConsistencyOfReplicatedPositions()
    {
        nodes().forEach(NodeRunner::checkConsistencyOfReplicatedPositions);
    }

    private boolean noNodesReceivedMessage(final long position)
    {
        return notReceivedMessage(node1, position) &&
               notReceivedMessage(node2, position) &&
               notReceivedMessage(node3, position);
    }

    private void checkClusterStable()
    {
        for (int i = 0; i < 100; i++)
        {
            pollAll();
        }

        eventuallyOneLeaderAndTwoFollowersWithSameLeader();

        assertAllNodesSeeSameLeader();

        DebugLogger.log(RAFT, "Cluster Stable");
    }

    private void awaitLeadershipConsensus()
    {
        assertEventuallyTrue(
            "Nodes don't agree on the leader",
            () ->
            {
                pollAll();
                return nodesAgreeOnLeader();
            });
    }

    private void assertLeadershipConsensus()
    {
        assertTrue("Nodes don't agree on the leader" + clusterInfo(), nodesAgreeOnLeader());
    }

    private void assertAllNodesSeeSameLeader()
    {
        final int leaderSessionId = node1.leaderSessionId();
        assertEquals("1 and 2 disagree on leader" + clusterInfo(), leaderSessionId, node2.leaderSessionId());
        assertEquals("1 and 3 disagree on leader" + clusterInfo(), leaderSessionId, node3.leaderSessionId());
    }

    private boolean notReceivedMessage(final NodeRunner node, final long position)
    {
        return !receivedMessage(node, position);
    }

    private boolean receivedMessage(final NodeRunner node, final long position)
    {
        return node.replicatedPosition() == position;
    }

    // TODO: what happens if the leader changes in the middle of this test?
    private long sendMessageTo(final NodeRunner leader)
    {
        final ClusterablePublication publication = leader.publication();

        return withTimeout(
            "Failed to send message",
            () ->
            {
                final long position = publication.tryClaim(BUFFER_SIZE, bufferClaim);
                if (position > 0)
                {
                    bufferClaim.buffer().putBytes(bufferClaim.offset(), buffer, 0, BUFFER_SIZE);
                    bufferClaim.commit();
                    return Optional.of(position);
                }

                pollAll();
                return Optional.empty();
            },
            5_000L);
    }

    private void pollAll()
    {
        poll(allNodes);
    }

    private void poll(final NodeRunner... nodes)
    {
        final int fragmentLimit = 10;
        for (final NodeRunner node : nodes)
        {
            node.poll(fragmentLimit);
        }
    }

    private void assertEventuallyFindsLeaderIn(final NodeRunner... nodes)
    {
        assertEventuallyTrue(
            "Never finds leader",
            () ->
            {
                pollAll();
                return foundLeader(nodes);
            });
    }

    private boolean foundLeader(final NodeRunner... nodes)
    {
        final long leaderCount = Stream.of(nodes).filter(NodeRunner::isLeader).count();
        return leaderCount == 1;
    }

    private void eventuallyOneLeaderAndTwoFollowers()
    {
        eventuallyOneLeaderAndTwoFollowers(() -> true);
    }

    private void eventuallyOneLeaderAndTwoFollowersWithSameLeader()
    {
        eventuallyOneLeaderAndTwoFollowers(this::nodesAgreeOnLeader);
    }

    private void eventuallyOneLeaderAndTwoFollowers(final BooleanSupplier predicate)
    {
        assertEventuallyTrue(
            "failed to find one leader with two followers",
            () ->
            {
                pollAll();
                return oneLeaderAndTwoFollowers() && predicate.getAsBoolean();
            });
    }

    private boolean nodesAgreeOnLeader()
    {
        return nodesAgreeOn(NodeRunner::leaderSessionId);
    }

    private boolean nodesAgreeOnLeadershipTerm()
    {
        return nodesAgreeOn(NodeRunner::leadershipTerm);
    }

    private boolean nodesAgreeOn(final ToIntFunction<NodeRunner> property)
    {
        final NodeRunner[] allNodes = this.allNodes;
        final int value = property.applyAsInt(allNodes[0]);
        for (int i = 1; i < allNodes.length; i++)
        {
            if (property.applyAsInt(allNodes[i]) != value)
            {
                return false;
            }
        }
        return true;
    }

    private boolean oneLeaderAndTwoFollowers()
    {
        int leaderCount = 0;
        int followerCount = 0;

        for (final NodeRunner node : allNodes)
        {
            if (node.isLeader())
            {
                leaderCount++;
            }
            else if (node.clusterAgent().isFollower())
            {
                followerCount++;
            }
        }

        return leaderCount == 1 && followerCount == 2;
    }

    private NodeRunner leader()
    {
        return nodes()
            .filter(NodeRunner::isLeader)
            .findFirst()
            .get(); // Just error the test if there's not a leader
    }

    private NodeRunner[] followers()
    {
        return nodes().filter((node) -> !node.isLeader()).toArray(NodeRunner[]::new);
    }

    private void assertEventuallyTrue(
        final String message,
        final BooleanSupplier test)
    {
        Timing.assertEventuallyTrue(
            () -> message + clusterInfo(),
            test,
            DEFAULT_TIMEOUT_IN_MS,
            () -> {}
        );
    }

    private String clusterInfo()
    {
        return nodes()
            .map(
                (runner) ->
                {
                    final ClusterAgent agent = runner.clusterAgent();
                    final TermState termState = agent.termState();
                    final int leaderSessionId = termState.leaderSessionId().get();
                    final int leadershipTerm = termState.leadershipTerm();
                    final long consensusPosition = termState.consensusPosition().get();
                    final int ourSessionId = agent.ourSessionId();
                    // Should be the same as last applied position
                    final long handledPosition = runner.replicatedPosition();
                    final long dataPubPosition = runner.publication().position();
                    final long archivedPosition = runner.archivedPosition();

                    return String.format(
                        "%s %d: leader=%d, term=%d, us=%d, handledPos=%d, csnsPos=%d, pubPos=%d, archPos=%d",
                        state(agent),
                        agent.nodeId(),
                        leaderSessionId,
                        leadershipTerm,
                        ourSessionId,
                        handledPosition,
                        consensusPosition,
                        dataPubPosition,
                        archivedPosition);
                })
            .collect(joining(lineSeparator(), lineSeparator(), lineSeparator()));
    }

    private String state(final ClusterAgent agent)
    {
        if (agent.isLeader())
        {
            return "leader   ";
        }

        if (agent.isFollower())
        {
            return "follower ";
        }

        if (agent.isCandidate())
        {
            return "candidate";
        }

        throw new IllegalStateException("Unknown state");
    }

    private Stream<NodeRunner> nodes()
    {
        return Stream.of(node1, node2, node3);
    }
}
