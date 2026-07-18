import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import java.util.Properties;

public class BasicSipClient implements SipListener {

    private SipProvider sipProvider;
    private MessageFactory messageFactory;
    private HeaderFactory headerFactory;
    private AddressFactory addressFactory;

    public void init() throws Exception {
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName("gov.nist");

        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", "DemoStack");
        properties.setProperty("gov.nist.sip.STACK_LOGGER", "gov.nist.core.LogWriter");
        
        SipStack sipStack = sipFactory.createSipStack(properties);
        ListeningPoint lp = sipStack.createListeningPoint("127.0.0.1", 5060, "udp");
        
        this.sipProvider = sipStack.createSipProvider(lp);
        this.sipProvider.addSipListener(this);
        
        // Initialize all required JAIN factories
        this.messageFactory = sipFactory.createMessageFactory();
        this.headerFactory = sipFactory.createHeaderFactory();
        this.addressFactory = sipFactory.createAddressFactory();

        System.out.println("SIP Stack running on 127.0.0.1:5060...");
    }

    public void processRequest(RequestEvent e) {
        Request request = e.getRequest();
        String method = request.getMethod();
        System.out.println("Received Request: " + method);

        try {
            if (method.equals(Request.INVITE)) {
                Response response = messageFactory.createResponse(Response.OK, request);
                
                // Add the missing Contact Header so the sender knows where to route the BYE
                SipURI contactUri = addressFactory.createSipURI("server", "127.0.0.1:5060");
                Address contactAddress = addressFactory.createAddress(contactUri);
                ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
                response.addHeader(contactHeader);

                sipProvider.sendResponse(response);
                System.out.println("Sent Response: 200 OK (for INVITE)");
            } 
            else if (method.equals(Request.BYE)) {
                Response response = messageFactory.createResponse(Response.OK, request);
                sipProvider.sendResponse(response);
                System.out.println("Sent Response: 200 OK (for BYE)");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void processResponse(ResponseEvent e) {}
    public void processTimeout(TimeoutEvent e) {}
    public void processTransactionTerminated(TransactionTerminatedEvent e) {}
    public void processDialogTerminated(DialogTerminatedEvent e) {}
    public void processIOException(IOExceptionEvent e) {}

    public static void main(String[] args) throws Exception {
        new BasicSipClient().init();
    }
}

