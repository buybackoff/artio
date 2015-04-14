/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.session;

import org.junit.Test;
import uk.co.real_logic.fix_gateway.FixGateway;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.session.SessionState.*;

public class InitiatorSessionTest extends AbstractSessionTest
{
    private final FixGateway mockGateway = mock(FixGateway.class);

    private InitiatorSession session = new InitiatorSession(HEARTBEAT_INTERVAL, CONNECTION_ID, fakeClock, mockProxy,
        mockPublication, null, mockGateway, SESSION_ID);

    @Test
    public void shouldInitiallyBeConnected()
    {
        assertEquals(CONNECTED, session.state());
    }

    @Test
    public void shouldActivateUponLogonResponse()
    {
        session.state(SENT_LOGON);

        onLogon(1);

        assertState(ACTIVE);
        verify(mockProxy).setupSession(SESSION_ID, SESSION_KEY);
        verifyNoFurtherMessages();
    }

    @Test
    public void shouldDisconnectIfLowSeqNo()
    {
        session.lastReceivedMsgSeqNum(5);

        session.state(SENT_LOGON);
        onLogon(1);

        verifyDisconnect();
    }

    @Test
    public void shouldAttemptLogonWhenConnected()
    {
        session.poll(0);

        verifyLogon();

        assertEquals(1, session.sentSeqNum());
    }

    @Test
    public void shouldAttemptLogonOnlyOnce()
    {
        session.poll(0);

        session.poll(10);

        session.poll(20);

        verifyLogon();
    }

    @Test
    public void shouldNotifyGatewayWhenLoggedIn()
    {
        session.state(SENT_LOGON);

        onLogon(1);

        verify(mockGateway).onInitiatorSessionActive(session);
    }

    @Test
    public void shouldNotifyGatewayWhenLoggedInOnce()
    {
        session.state(SENT_LOGON);

        onLogon(1);

        onLogon(2);

        verify(mockGateway, times(1)).onInitiatorSessionActive(session);
    }

    private void verifyLogon()
    {
        verify(mockProxy, times(1)).logon(HEARTBEAT_INTERVAL, 1);
    }

    protected Session session()
    {
        return session;
    }
}