package org.apache.camel.processor.resequencer;

import org.apache.camel.ContextTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.seda.SedaEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class ResequencerAsyncProducerTest extends ContextTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ResequencerAsyncProducerTest.class);

    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                from("direct:start").resequence(body()).batch().size(2).timeout(3000).to("seda:result");
            }
        };
    }

    public void test() throws Exception {
        for (int i = 0; i < 100; i++) {
            testIteration(i);
        }
    }

    private void testIteration(int i) throws Exception {
        LOG.info("Run #{}", i);

        template.sendBody("direct:start", "4");
        template.sendBody("direct:start", "1");

        template.sendBody("direct:start", "3");
        template.sendBody("direct:start", "2");

        BlockingQueue<Exchange> se = context.getEndpoint("seda:result", SedaEndpoint.class).getQueue();

        assertEquals("1", se.take().getIn().getBody());
        assertEquals("4", se.take().getIn().getBody());

        assertEquals("2", se.take().getIn().getBody());
        assertEquals("3", se.take().getIn().getBody());
    }
}
