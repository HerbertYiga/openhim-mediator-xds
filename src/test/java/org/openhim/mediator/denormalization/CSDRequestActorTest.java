package org.openhim.mediator.denormalization;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.JavaTestKit;
import org.apache.http.HttpStatus;
import org.junit.*;
import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.dummies.MockLauncher;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;
import org.openhim.mediator.messages.ResolveFacilityIdentifier;
import org.openhim.mediator.messages.ResolveFacilityIdentifierResponse;
import org.openhim.mediator.messages.ResolveHealthcareWorkerIdentifier;
import org.openhim.mediator.messages.ResolveHealthcareWorkerIdentifierResponse;
import scala.concurrent.duration.Duration;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class CSDRequestActorTest {
    private abstract static class MockHTTPServer extends UntypedActor {
        abstract String getResponse();

        @Override
        public void onReceive(Object msg) throws Exception {
            if (msg instanceof MediatorHTTPRequest) {
                assertEquals("POST", ((MediatorHTTPRequest) msg).getMethod());
                assertEquals("http", ((MediatorHTTPRequest) msg).getScheme());
                MediatorHTTPResponse rMsg = new MediatorHTTPResponse(
                        (MediatorHTTPRequest)msg, getResponse(), HttpStatus.SC_OK, Collections.<String, String>emptyMap()
                );
                getSender().tell(rMsg, getSelf());
            } else {
                unhandled(msg);
            }
        }
    }

    private static class HCWMock extends MockHTTPServer {
        @Override
        String getResponse() {
            return "<CSD xmlns='urn:ihe:iti:csd:2013'>\n"
                    + "  <serviceDirectory/>\n"
                    + "  <organizationDirectory/>\n"
                    + "  <providerDirectory>\n"
                    + "    <provider entityID='urn:oid:1.2.3.1234'>\n"
                    + "      <!-- POTENTIALLY LARGE AMOUNT OF CONTENT ON THE PROVIDER -->\n"
                    + "    </provider>\n"
                    + "  </providerDirectory>\n"
                    + "  <facilityDirectory/>\n"
                    + "</CSD>\n";
        }
    }

    private static class FacilityMock extends MockHTTPServer {
        @Override
        String getResponse() {
            return "<CSD xmlns='urn:ihe:iti:csd:2013'>\n"
                    + "  <serviceDirectory/>\n"
                    + "  <organizationDirectory/>\n"
                    + "  <providerDirectory/>\n"
                    + "  <facilityDirectory>\n"
                    + "    <facility entityID='urn:oid:1.2.3.2345'>\n"
                    + "      <!-- POTENTIALLY LARGE AMOUNT OF CONTENT ON THE FACILITY -->\n"
                    + "    </facility>\n"
                    + "  </facilityDirectory>\n"
                    + "</CSD>\n";
        }
    }

    static ActorSystem system;
    MediatorConfig testConfig;


    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("csd-unit-test");
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    @Before
    public void setUp() throws Exception {
        testConfig = new MediatorConfig();
        testConfig.setProperties("mediator-unit-test.properties");
    }

    private void stubWith(Class<? extends MockHTTPServer> clazz) {
        testConfig.setName(UUID.randomUUID().toString());
        MockLauncher.launchActors(system, testConfig.getName(), Collections.singletonList(new MockLauncher.ActorToLaunch("http-connector", clazz)));
    }

    private void stubForHealthcareWorkerLookup() {
        stubWith(HCWMock.class);
    }

    private void stubForFacilityLookup() {
        stubWith(FacilityMock.class);
    }

    private void clearStub() {
        MockLauncher.clearActors(system, testConfig.getName());
    }

    @Test
    public void buildIdentifier_shouldextractAndSetUUIDAndAppropriateAssigningAuthority() throws Exception {
        // Given
        String id = "urn:uuid:5b311c3b-67f3-4a7b-9a96-b2dd127af828";

        // When
        Identifier result = CSDRequestActor.buildIdentifier(id);

        // Then
        assertNotNull(result);
        assertEquals(result.getIdentifier(), "5b311c3b-67f3-4a7b-9a96-b2dd127af828");
        assertEquals(result.getAssigningAuthority().getAssigningAuthorityId(), "2.25");
    }

    @Test
    public void buildIdentifier_shouldextractAndSetOIDAndAppropriateAssigningAuthority() throws Exception {
        // Given
        String id = "urn:oid:1.2.3.55555";

        // When
        Identifier result = CSDRequestActor.buildIdentifier(id);

        // Then
        assertNotNull(result);
        assertEquals(result.getIdentifier(), "55555");
        assertEquals(result.getAssigningAuthority().getAssigningAuthorityId(), "1.2.3");
    }

    @Test
    public void resolveHealthcareWorkerIdentifier() throws Exception {
        stubForHealthcareWorkerLookup();

        new JavaTestKit(system) {{
            try {
                ActorRef actor = system.actorOf(Props.create(CSDRequestActor.class, testConfig));

                Identifier testId = new Identifier("1234", new AssigningAuthority("", "testauth"));
                ResolveHealthcareWorkerIdentifier testMsg = new ResolveHealthcareWorkerIdentifier(
                        getRef(), getRef(), testId, new AssigningAuthority("", "not used")
                );

                actor.tell(testMsg, getRef());

                ResolveHealthcareWorkerIdentifierResponse response = expectMsgClass(
                        Duration.create(100, TimeUnit.MILLISECONDS),
                        ResolveHealthcareWorkerIdentifierResponse.class
                );

                assertEquals("1234", response.getIdentifier().getIdentifier());
                assertEquals("1.2.3", response.getIdentifier().getAssigningAuthority().getAssigningAuthorityId());
            } finally {
                clearStub();
            }
        }};
    }

    @Test
    public void resolveFacilityIdentifier() throws Exception {
        stubForFacilityLookup();

        new JavaTestKit(system) {{
            try {
                ActorRef actor = system.actorOf(Props.create(CSDRequestActor.class, testConfig));

                Identifier testId = new Identifier("1234", new AssigningAuthority("", "testauth"));
                ResolveFacilityIdentifier testMsg = new ResolveFacilityIdentifier(
                        getRef(), getRef(), testId, new AssigningAuthority("", "not used")
                );

                actor.tell(testMsg, getRef());

                ResolveFacilityIdentifierResponse response = expectMsgClass(
                        Duration.create(100, TimeUnit.MILLISECONDS),
                        ResolveFacilityIdentifierResponse.class
                );

                assertEquals("2345", response.getIdentifier().getIdentifier());
                assertEquals("1.2.3", response.getIdentifier().getAssigningAuthority().getAssigningAuthorityId());
            } finally {
                clearStub();
            }
        }};
    }
}