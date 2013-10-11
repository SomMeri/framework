/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.aria.client.LiveValue;
import com.google.gwt.aria.client.RelevantValue;
import com.google.gwt.aria.client.Roles;
import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.SimpleEventBus;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.http.client.URL;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.regexp.shared.MatchResult;
import com.google.gwt.regexp.shared.RegExp;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.ClosingHandler;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.ApplicationConfiguration.ErrorMessage;
import com.vaadin.client.ResourceLoader.ResourceLoadEvent;
import com.vaadin.client.ResourceLoader.ResourceLoadListener;
import com.vaadin.client.communication.HasJavaScriptConnectorHelper;
import com.vaadin.client.communication.JavaScriptMethodInvocation;
import com.vaadin.client.communication.JsonDecoder;
import com.vaadin.client.communication.JsonEncoder;
import com.vaadin.client.communication.PushConnection;
import com.vaadin.client.communication.RpcManager;
import com.vaadin.client.communication.StateChangeEvent;
import com.vaadin.client.componentlocator.ComponentLocator;
import com.vaadin.client.extensions.AbstractExtensionConnector;
import com.vaadin.client.metadata.ConnectorBundleLoader;
import com.vaadin.client.metadata.Method;
import com.vaadin.client.metadata.NoDataException;
import com.vaadin.client.metadata.Property;
import com.vaadin.client.metadata.Type;
import com.vaadin.client.metadata.TypeData;
import com.vaadin.client.ui.AbstractComponentConnector;
import com.vaadin.client.ui.AbstractConnector;
import com.vaadin.client.ui.VContextMenu;
import com.vaadin.client.ui.VNotification;
import com.vaadin.client.ui.VNotification.HideEvent;
import com.vaadin.client.ui.VOverlay;
import com.vaadin.client.ui.dd.VDragAndDropManager;
import com.vaadin.client.ui.ui.UIConnector;
import com.vaadin.client.ui.window.WindowConnector;
import com.vaadin.shared.AbstractComponentState;
import com.vaadin.shared.ApplicationConstants;
import com.vaadin.shared.Version;
import com.vaadin.shared.communication.LegacyChangeVariablesInvocation;
import com.vaadin.shared.communication.MethodInvocation;
import com.vaadin.shared.communication.SharedState;
import com.vaadin.shared.ui.ui.UIConstants;
import com.vaadin.shared.ui.ui.UIState.PushConfigurationState;

/**
 * This is the client side communication "engine", managing client-server
 * communication with its server side counterpart
 * com.vaadin.server.VaadinService.
 * 
 * Client-side connectors receive updates from the corresponding server-side
 * connector (typically component) as state updates or RPC calls. The connector
 * has the possibility to communicate back with its server side counter part
 * through RPC calls.
 * 
 * TODO document better
 * 
 * Entry point classes (widgetsets) define <code>onModuleLoad()</code>.
 */
public class ApplicationConnection {

    /**
     * Helper used to return two values when updating the connector hierarchy.
     */
    private static class ConnectorHierarchyUpdateResult {
        /**
         * Needed at a later point when the created events are fired
         */
        private JsArrayObject<ConnectorHierarchyChangeEvent> events = JavaScriptObject
                .createArray().cast();
        /**
         * Needed to know where captions might need to get updated
         */
        private FastStringSet parentChangedIds = FastStringSet.create();
    }

    public static final String MODIFIED_CLASSNAME = "v-modified";

    public static final String DISABLED_CLASSNAME = "v-disabled";

    public static final String REQUIRED_CLASSNAME_EXT = "-required";

    public static final String ERROR_CLASSNAME_EXT = "-error";

    public static final char VAR_BURST_SEPARATOR = '\u001d';

    public static final char VAR_ESCAPE_CHARACTER = '\u001b';

    /**
     * A string that, if found in a non-JSON response to a UIDL request, will
     * cause the browser to refresh the page. If followed by a colon, optional
     * whitespace, and a URI, causes the browser to synchronously load the URI.
     * 
     * <p>
     * This allows, for instance, a servlet filter to redirect the application
     * to a custom login page when the session expires. For example:
     * </p>
     * 
     * <pre>
     * if (sessionExpired) {
     *     response.setHeader(&quot;Content-Type&quot;, &quot;text/html&quot;);
     *     response.getWriter().write(
     *             myLoginPageHtml + &quot;&lt;!-- Vaadin-Refresh: &quot;
     *                     + request.getContextPath() + &quot; --&gt;&quot;);
     * }
     * </pre>
     */
    public static final String UIDL_REFRESH_TOKEN = "Vaadin-Refresh";

    // will hold the CSRF token once received
    private String csrfToken = "init";

    private final HashMap<String, String> resourcesMap = new HashMap<String, String>();

    /**
     * The pending method invocations that will be send to the server by
     * {@link #sendPendingCommand}. The key is defined differently based on
     * whether the method invocation is enqueued with lastonly. With lastonly
     * enabled, the method signature ( {@link MethodInvocation#getLastOnlyTag()}
     * ) is used as the key to make enable removing a previously enqueued
     * invocation. Without lastonly, an incremental id based on
     * {@link #lastInvocationTag} is used to get unique values.
     */
    private LinkedHashMap<String, MethodInvocation> pendingInvocations = new LinkedHashMap<String, MethodInvocation>();

    private int lastInvocationTag = 0;

    private WidgetSet widgetSet;

    private VContextMenu contextMenu = null;

    private final UIConnector uIConnector;

    protected boolean applicationRunning = false;

    private boolean hasActiveRequest = false;

    /**
     * Some browsers cancel pending XHR requests when a request that might
     * navigate away from the page starts (indicated by a beforeunload event).
     * In that case, we should just send the request again without displaying
     * any error.
     */
    private boolean retryCanceledActiveRequest = false;

    /**
     * Webkit will ignore outgoing requests while waiting for a response to a
     * navigation event (indicated by a beforeunload event). When this happens,
     * we should keep trying to send the request every now and then until there
     * is a response or until it throws an exception saying that it is already
     * being sent.
     */
    private boolean webkitMaybeIgnoringRequests = false;

    protected boolean cssLoaded = false;

    /** Parameters for this application connection loaded from the web-page */
    private ApplicationConfiguration configuration;

    /** List of pending variable change bursts that must be submitted in order */
    private final ArrayList<LinkedHashMap<String, MethodInvocation>> pendingBursts = new ArrayList<LinkedHashMap<String, MethodInvocation>>();

    /** Timer for automatic refirect to SessionExpiredURL */
    private Timer redirectTimer;

    /** redirectTimer scheduling interval in seconds */
    private int sessionExpirationInterval;

    private Date requestStartTime;

    private final LayoutManager layoutManager;

    private final RpcManager rpcManager;

    private PushConnection push;

    /**
     * If responseHandlingLocks contains any objects, response handling is
     * suspended until the collection is empty or a timeout has occurred.
     */
    private Set<Object> responseHandlingLocks = new HashSet<Object>();

    /**
     * Data structure holding information about pending UIDL messages.
     */
    private class PendingUIDLMessage {
        private Date start;
        private String jsonText;
        private ValueMap json;

        public PendingUIDLMessage(Date start, String jsonText, ValueMap json) {
            this.start = start;
            this.jsonText = jsonText;
            this.json = json;
        }

        public Date getStart() {
            return start;
        }

        public String getJsonText() {
            return jsonText;
        }

        public ValueMap getJson() {
            return json;
        }
    }

    /** Contains all UIDL messages received while response handling is suspended */
    private List<PendingUIDLMessage> pendingUIDLMessages = new ArrayList<PendingUIDLMessage>();

    /** The max timeout that response handling may be suspended */
    private static final int MAX_SUSPENDED_TIMEOUT = 5000;

    /** Event bus for communication events */
    private EventBus eventBus = GWT.create(SimpleEventBus.class);

    private int lastResponseId = -1;

    /**
     * The communication handler methods are called at certain points during
     * communication with the server. This allows for making add-ons that keep
     * track of different aspects of the communication.
     */
    public interface CommunicationHandler extends EventHandler {
        void onRequestStarting(RequestStartingEvent e);

        void onResponseHandlingStarted(ResponseHandlingStartedEvent e);

        void onResponseHandlingEnded(ResponseHandlingEndedEvent e);
    }

    public static class RequestStartingEvent extends ApplicationConnectionEvent {

        public static Type<CommunicationHandler> TYPE = new Type<CommunicationHandler>();

        public RequestStartingEvent(ApplicationConnection connection) {
            super(connection);
        }

        @Override
        public com.google.gwt.event.shared.GwtEvent.Type<CommunicationHandler> getAssociatedType() {
            return TYPE;
        }

        @Override
        protected void dispatch(CommunicationHandler handler) {
            handler.onRequestStarting(this);
        }
    }

    public static class ResponseHandlingEndedEvent extends
            ApplicationConnectionEvent {

        public static Type<CommunicationHandler> TYPE = new Type<CommunicationHandler>();

        public ResponseHandlingEndedEvent(ApplicationConnection connection) {
            super(connection);
        }

        @Override
        public com.google.gwt.event.shared.GwtEvent.Type<CommunicationHandler> getAssociatedType() {
            return TYPE;
        }

        @Override
        protected void dispatch(CommunicationHandler handler) {
            handler.onResponseHandlingEnded(this);
        }
    }

    public static abstract class ApplicationConnectionEvent extends
            GwtEvent<CommunicationHandler> {

        private ApplicationConnection connection;

        protected ApplicationConnectionEvent(ApplicationConnection connection) {
            this.connection = connection;
        }

        public ApplicationConnection getConnection() {
            return connection;
        }

    }

    public static class ResponseHandlingStartedEvent extends
            ApplicationConnectionEvent {

        public ResponseHandlingStartedEvent(ApplicationConnection connection) {
            super(connection);
        }

        public static Type<CommunicationHandler> TYPE = new Type<CommunicationHandler>();

        @Override
        public com.google.gwt.event.shared.GwtEvent.Type<CommunicationHandler> getAssociatedType() {
            return TYPE;
        }

        @Override
        protected void dispatch(CommunicationHandler handler) {
            handler.onResponseHandlingStarted(this);
        }
    }

    /**
     * Allows custom handling of communication errors.
     */
    public interface CommunicationErrorHandler {
        /**
         * Called when a communication error has occurred. Returning
         * <code>true</code> from this method suppresses error handling.
         * 
         * @param details
         *            A string describing the error.
         * @param statusCode
         *            The HTTP status code (e.g. 404, etc).
         * @return true if the error reporting should be suppressed, false to
         *         perform normal error reporting.
         */
        public boolean onError(String details, int statusCode);
    }

    private CommunicationErrorHandler communicationErrorDelegate = null;

    private VLoadingIndicator loadingIndicator;

    public static class MultiStepDuration extends Duration {
        private int previousStep = elapsedMillis();

        public void logDuration(String message) {
            logDuration(message, 0);
        }

        public void logDuration(String message, int minDuration) {
            int currentTime = elapsedMillis();
            int stepDuration = currentTime - previousStep;
            if (stepDuration >= minDuration) {
                VConsole.log(message + ": " + stepDuration + " ms");
            }
            previousStep = currentTime;
        }
    }

    public ApplicationConnection() {
        // Assuming UI data is eagerly loaded
        ConnectorBundleLoader.get().loadBundle(
                ConnectorBundleLoader.EAGER_BUNDLE_NAME, null);
        uIConnector = GWT.create(UIConnector.class);
        rpcManager = GWT.create(RpcManager.class);
        layoutManager = GWT.create(LayoutManager.class);
        layoutManager.setConnection(this);
        tooltip = GWT.create(VTooltip.class);
        loadingIndicator = GWT.create(VLoadingIndicator.class);
        loadingIndicator.setConnection(this);
    }

    public void init(WidgetSet widgetSet, ApplicationConfiguration cnf) {
        VConsole.log("Starting application " + cnf.getRootPanelId());
        VConsole.log("Using theme: " + cnf.getThemeName());

        VConsole.log("Vaadin application servlet version: "
                + cnf.getServletVersion());

        if (!cnf.getServletVersion().equals(Version.getFullVersion())) {
            VConsole.error("Warning: your widget set seems to be built with a different "
                    + "version than the one used on server. Unexpected "
                    + "behavior may occur.");
        }

        this.widgetSet = widgetSet;
        configuration = cnf;

        ComponentLocator componentLocator = new ComponentLocator(this);

        String appRootPanelName = cnf.getRootPanelId();
        // remove the end (window name) of autogenerated rootpanel id
        appRootPanelName = appRootPanelName.replaceFirst("-\\d+$", "");

        initializeTestbenchHooks(componentLocator, appRootPanelName);

        initializeClientHooks();

        uIConnector.init(cnf.getRootPanelId(), this);

        tooltip.setOwner(uIConnector.getWidget());

        getLoadingIndicator().show();

        scheduleHeartbeat();

        Window.addWindowClosingHandler(new ClosingHandler() {
            @Override
            public void onWindowClosing(ClosingEvent event) {
                /*
                 * Set some flags to avoid potential problems with XHR requests,
                 * see javadocs of the flags for details
                 */
                if (hasActiveRequest()) {
                    retryCanceledActiveRequest = true;
                }

                webkitMaybeIgnoringRequests = true;
            }
        });

        // Ensure the overlay container is added to the dom and set as a live
        // area for assistive devices
        Element overlayContainer = VOverlay.getOverlayContainer(this);
        Roles.getAlertRole().setAriaLiveProperty(overlayContainer,
                LiveValue.ASSERTIVE);
        VOverlay.setOverlayContainerLabel(this,
                getUIConnector().getState().overlayContainerLabel);
        Roles.getAlertRole().setAriaRelevantProperty(overlayContainer,
                RelevantValue.ADDITIONS);
    }

    /**
     * Starts this application. Don't call this method directly - it's called by
     * {@link ApplicationConfiguration#startNextApplication()}, which should be
     * called once this application has started (first response received) or
     * failed to start. This ensures that the applications are started in order,
     * to avoid session-id problems.
     * 
     */
    public void start() {
        String jsonText = configuration.getUIDL();
        if (jsonText == null) {
            // inital UIDL not in DOM, request later
            repaintAll();
        } else {
            // Update counter so TestBench knows something is still going on
            hasActiveRequest = true;

            // initial UIDL provided in DOM, continue as if returned by request
            handleJSONText(jsonText, -1);
        }
    }

    private native void initializeTestbenchHooks(
            ComponentLocator componentLocator, String TTAppId)
    /*-{
        var ap = this;
        var client = {};
        client.isActive = $entry(function() {
            return ap.@com.vaadin.client.ApplicationConnection::hasActiveRequest()()
                    || ap.@com.vaadin.client.ApplicationConnection::isExecutingDeferredCommands()();
        });
        var vi = ap.@com.vaadin.client.ApplicationConnection::getVersionInfo()();
        if (vi) {
            client.getVersionInfo = function() {
                return vi;
            }
        }

        client.getProfilingData = $entry(function() {
            var pd = [
                ap.@com.vaadin.client.ApplicationConnection::lastProcessingTime,
                    ap.@com.vaadin.client.ApplicationConnection::totalProcessingTime
                ];
            pd = pd.concat(ap.@com.vaadin.client.ApplicationConnection::serverTimingInfo);
            pd[pd.length] = ap.@com.vaadin.client.ApplicationConnection::bootstrapTime;
            return pd;
        });

        client.getElementByPath = $entry(function(id) {
            return componentLocator.@com.vaadin.client.componentlocator.ComponentLocator::getElementByPath(Ljava/lang/String;)(id);
        });
        client.getElementByPathStartingAt = $entry(function(id, element) {
            return componentLocator.@com.vaadin.client.componentlocator.ComponentLocator::getElementByPathStartingAt(Ljava/lang/String;Lcom/google/gwt/user/client/Element;)(id, element);
        });
        client.getPathForElement = $entry(function(element) {
            return componentLocator.@com.vaadin.client.componentlocator.ComponentLocator::getPathForElement(Lcom/google/gwt/user/client/Element;)(element);
        });
        client.initializing = false;

        $wnd.vaadin.clients[TTAppId] = client;
    }-*/;

    private static native final int calculateBootstrapTime()
    /*-{
        if ($wnd.performance && $wnd.performance.timing) {
            return (new Date).getTime() - $wnd.performance.timing.responseStart;
        } else {
            // performance.timing not supported
            return -1;
        }
    }-*/;

    /**
     * Helper for tt initialization
     */
    private JavaScriptObject getVersionInfo() {
        return configuration.getVersionInfoJSObject();
    }

    /**
     * Publishes a JavaScript API for mash-up applications.
     * <ul>
     * <li><code>vaadin.forceSync()</code> sends pending variable changes, in
     * effect synchronizing the server and client state. This is done for all
     * applications on host page.</li>
     * <li><code>vaadin.postRequestHooks</code> is a map of functions which gets
     * called after each XHR made by vaadin application. Note, that it is
     * attaching js functions responsibility to create the variable like this:
     * 
     * <code><pre>
     * if(!vaadin.postRequestHooks) {vaadin.postRequestHooks = new Object();}
     * postRequestHooks.myHook = function(appId) {
     *          if(appId == "MyAppOfInterest") {
     *                  // do the staff you need on xhr activity
     *          }
     * }
     * </pre></code> First parameter passed to these functions is the identifier
     * of Vaadin application that made the request.
     * </ul>
     * 
     * TODO make this multi-app aware
     */
    private native void initializeClientHooks()
    /*-{
    	var app = this;
    	var oldSync;
    	if ($wnd.vaadin.forceSync) {
    		oldSync = $wnd.vaadin.forceSync;
    	}
    	$wnd.vaadin.forceSync = $entry(function() {
    		if (oldSync) {
    			oldSync();
    		}
    		app.@com.vaadin.client.ApplicationConnection::sendPendingVariableChanges()();
    	});
    	var oldForceLayout;
    	if ($wnd.vaadin.forceLayout) {
    		oldForceLayout = $wnd.vaadin.forceLayout;
    	}
    	$wnd.vaadin.forceLayout = $entry(function() {
    		if (oldForceLayout) {
    			oldForceLayout();
    		}
    		app.@com.vaadin.client.ApplicationConnection::forceLayout()();
    	});
    }-*/;

    /**
     * Runs possibly registered client side post request hooks. This is expected
     * to be run after each uidl request made by Vaadin application.
     * 
     * @param appId
     */
    private static native void runPostRequestHooks(String appId)
    /*-{
    	if ($wnd.vaadin.postRequestHooks) {
    		for ( var hook in $wnd.vaadin.postRequestHooks) {
    			if (typeof ($wnd.vaadin.postRequestHooks[hook]) == "function") {
    				try {
    					$wnd.vaadin.postRequestHooks[hook](appId);
    				} catch (e) {
    				}
    			}
    		}
    	}
    }-*/;

    /**
     * If on Liferay and logged in, ask the client side session management
     * JavaScript to extend the session duration.
     * 
     * Otherwise, Liferay client side JavaScript will explicitly expire the
     * session even though the server side considers the session to be active.
     * See ticket #8305 for more information.
     */
    protected native void extendLiferaySession()
    /*-{
    if ($wnd.Liferay && $wnd.Liferay.Session) {
        $wnd.Liferay.Session.extend();
        // if the extend banner is visible, hide it
        if ($wnd.Liferay.Session.banner) {
            $wnd.Liferay.Session.banner.remove();
        }
    }
    }-*/;

    /**
     * Indicates whether or not there are currently active UIDL requests. Used
     * internally to sequence requests properly, seldom needed in Widgets.
     * 
     * @return true if there are active requests
     */
    public boolean hasActiveRequest() {
        return hasActiveRequest;
    }

    private String getRepaintAllParameters() {
        // collect some client side data that will be sent to server on
        // initial uidl request
        String nativeBootstrapParameters = getNativeBrowserDetailsParameters(getConfiguration()
                .getRootPanelId());
        // TODO figure out how client and view size could be used better on
        // server. screen size can be accessed via Browser object, but other
        // values currently only via transaction listener.
        String parameters = ApplicationConstants.URL_PARAMETER_REPAINT_ALL
                + "=1&" + nativeBootstrapParameters;
        return parameters;
    }

    /**
     * Gets the browser detail parameters that are sent by the bootstrap
     * javascript for two-request initialization.
     * 
     * @param parentElementId
     * @return
     */
    private static native String getNativeBrowserDetailsParameters(
            String parentElementId)
    /*-{
       return $wnd.vaadin.getBrowserDetailsParameters(parentElementId);
    }-*/;

    protected void repaintAll() {
        makeUidlRequest("", getRepaintAllParameters());
    }

    /**
     * Requests an analyze of layouts, to find inconsistencies. Exclusively used
     * for debugging during development.
     * 
     * @deprecated as of 7.1. Replaced by {@link UIConnector#analyzeLayouts()}
     */
    @Deprecated
    public void analyzeLayouts() {
        getUIConnector().analyzeLayouts();
    }

    /**
     * Sends a request to the server to print details to console that will help
     * the developer to locate the corresponding server-side connector in the
     * source code.
     * 
     * @param serverConnector
     * @deprecated as of 7.1. Replaced by
     *             {@link UIConnector#showServerDebugInfo(ServerConnector)}
     */
    @Deprecated
    void highlightConnector(ServerConnector serverConnector) {
        getUIConnector().showServerDebugInfo(serverConnector);
    }

    /**
     * Makes an UIDL request to the server.
     * 
     * @param requestData
     *            Data that is passed to the server.
     * @param extraParams
     *            Parameters that are added as GET parameters to the url.
     *            Contains key=value pairs joined by & characters or is empty if
     *            no parameters should be added. Should not start with any
     *            special character.
     */
    protected void makeUidlRequest(final String requestData,
            final String extraParams) {
        startRequest();
        // Security: double cookie submission pattern
        final String payload = getCsrfToken() + VAR_BURST_SEPARATOR
                + requestData;
        VConsole.log("Making UIDL Request with params: " + payload);
        String uri = translateVaadinUri(ApplicationConstants.APP_PROTOCOL_PREFIX
                + ApplicationConstants.UIDL_PATH + '/');

        if (extraParams != null && extraParams.length() > 0) {
            uri = addGetParameters(uri, extraParams);
        }
        uri = addGetParameters(uri, UIConstants.UI_ID_PARAMETER + "="
                + configuration.getUIId());

        doUidlRequest(uri, payload);

    }

    /**
     * Sends an asynchronous or synchronous UIDL request to the server using the
     * given URI.
     * 
     * @param uri
     *            The URI to use for the request. May includes GET parameters
     * @param payload
     *            The contents of the request to send
     */
    protected void doUidlRequest(final String uri, final String payload) {
        RequestCallback requestCallback = new RequestCallback() {
            @Override
            public void onError(Request request, Throwable exception) {
                handleCommunicationError(exception.getMessage(), -1);
            }

            private void handleCommunicationError(String details, int statusCode) {
                if (!handleErrorInDelegate(details, statusCode)) {
                    showCommunicationError(details, statusCode);
                }
                endRequest();
            }

            @Override
            public void onResponseReceived(Request request, Response response) {
                VConsole.log("Server visit took "
                        + String.valueOf((new Date()).getTime()
                                - requestStartTime.getTime()) + "ms");

                int statusCode = response.getStatusCode();

                switch (statusCode) {
                case 0:
                    if (retryCanceledActiveRequest) {
                        /*
                         * Request was most likely canceled because the browser
                         * is maybe navigating away from the page. Just send the
                         * request again without displaying any error in case
                         * the navigation isn't carried through.
                         */
                        retryCanceledActiveRequest = false;
                        doUidlRequest(uri, payload);
                    } else {
                        handleCommunicationError(
                                "Invalid status code 0 (server down?)",
                                statusCode);
                    }
                    return;

                case 401:
                    /*
                     * Authorization has failed. Could be that the session has
                     * timed out and the container is redirecting to a login
                     * page.
                     */
                    showAuthenticationError("");
                    endRequest();
                    return;

                case 503:
                    /*
                     * We'll assume msec instead of the usual seconds. If
                     * there's no Retry-After header, handle the error like a
                     * 500, as per RFC 2616 section 10.5.4.
                     */
                    String delay = response.getHeader("Retry-After");
                    if (delay != null) {
                        VConsole.log("503, retrying in " + delay + "msec");
                        (new Timer() {
                            @Override
                            public void run() {
                                doUidlRequest(uri, payload);
                            }
                        }).schedule(Integer.parseInt(delay));
                        return;
                    }
                }

                if ((statusCode / 100) == 4) {
                    // Handle all 4xx errors the same way as (they are
                    // all permanent errors)
                    showCommunicationError(
                            "UIDL could not be read from server. Check servlets mappings. Error code: "
                                    + statusCode, statusCode);
                    endRequest();
                    return;
                } else if ((statusCode / 100) == 5) {
                    // Something's wrong on the server, there's nothing the
                    // client can do except maybe try again.
                    handleCommunicationError("Server error. Error code: "
                            + statusCode, statusCode);
                    return;
                }

                String contentType = response.getHeader("Content-Type");
                if (contentType == null
                        || !contentType.startsWith("application/json")) {
                    /*
                     * A servlet filter or equivalent may have intercepted the
                     * request and served non-UIDL content (for instance, a
                     * login page if the session has expired.) If the response
                     * contains a magic substring, do a synchronous refresh. See
                     * #8241.
                     */
                    MatchResult refreshToken = RegExp.compile(
                            UIDL_REFRESH_TOKEN + "(:\\s*(.*?))?(\\s|$)").exec(
                            response.getText());
                    if (refreshToken != null) {
                        redirect(refreshToken.getGroup(2));
                        return;
                    }
                }

                // for(;;);[realjson]
                final String jsonText = response.getText().substring(9,
                        response.getText().length() - 1);
                handleJSONText(jsonText, statusCode);
            }
        };
        if (push != null) {
            push.push(payload);
        } else {
            try {
                doAjaxRequest(uri, payload, requestCallback);
            } catch (RequestException e) {
                VConsole.error(e);
                endRequest();
            }
        }
    }

    /**
     * Handles received UIDL JSON text, parsing it, and passing it on to the
     * appropriate handlers, while logging timing information.
     * 
     * @param jsonText
     * @param statusCode
     */
    private void handleJSONText(String jsonText, int statusCode) {
        final Date start = new Date();
        final ValueMap json;
        try {
            json = parseJSONResponse(jsonText);
        } catch (final Exception e) {
            endRequest();
            showCommunicationError(e.getMessage() + " - Original JSON-text:"
                    + jsonText, statusCode);
            return;
        }

        VConsole.log("JSON parsing took "
                + (new Date().getTime() - start.getTime()) + "ms");
        if (applicationRunning) {
            handleReceivedJSONMessage(start, jsonText, json);
        } else {
            applicationRunning = true;
            handleWhenCSSLoaded(jsonText, json);
        }
    }

    /**
     * Sends an asynchronous UIDL request to the server using the given URI.
     * 
     * @param uri
     *            The URI to use for the request. May includes GET parameters
     * @param payload
     *            The contents of the request to send
     * @param requestCallback
     *            The handler for the response
     * @throws RequestException
     *             if the request could not be sent
     */
    protected void doAjaxRequest(String uri, String payload,
            RequestCallback requestCallback) throws RequestException {
        RequestBuilder rb = new RequestBuilder(RequestBuilder.POST, uri);
        // TODO enable timeout
        // rb.setTimeoutMillis(timeoutMillis);
        // TODO this should be configurable
        rb.setHeader("Content-Type", "text/plain;charset=utf-8");
        rb.setRequestData(payload);
        rb.setCallback(requestCallback);

        final Request request = rb.send();
        if (webkitMaybeIgnoringRequests && BrowserInfo.get().isWebkit()) {
            final int retryTimeout = 250;
            new Timer() {
                @Override
                public void run() {
                    // Use native js to access private field in Request
                    if (resendRequest(request) && webkitMaybeIgnoringRequests) {
                        // Schedule retry if still needed
                        schedule(retryTimeout);
                    }
                }
            }.schedule(retryTimeout);
        }
    }

    private static native boolean resendRequest(Request request)
    /*-{
        var xhr = request.@com.google.gwt.http.client.Request::xmlHttpRequest
        if (xhr.readyState != 1) {
            // Progressed to some other readyState -> no longer blocked
            return false;
        }
        try {
            xhr.send();
            return true;
        } catch (e) {
            // send throws exception if it is running for real
            return false;
        }
    }-*/;

    int cssWaits = 0;

    /**
     * Holds the time spent rendering the last request
     */
    protected int lastProcessingTime;

    /**
     * Holds the total time spent rendering requests during the lifetime of the
     * session.
     */
    protected int totalProcessingTime;

    /**
     * Holds the time it took to load the page and render the first view. 0
     * means that this value has not yet been calculated because the first view
     * has not yet been rendered (or that your browser is very fast). -1 means
     * that the browser does not support the performance.timing feature used to
     * get this measurement.
     */
    private int bootstrapTime;

    /**
     * Holds the timing information from the server-side. How much time was
     * spent servicing the last request and how much time has been spent
     * servicing the session so far. These values are always one request behind,
     * since they cannot be measured before the request is finished.
     */
    private ValueMap serverTimingInfo;

    static final int MAX_CSS_WAITS = 100;

    protected void handleWhenCSSLoaded(final String jsonText,
            final ValueMap json) {
        if (!isCSSLoaded() && cssWaits < MAX_CSS_WAITS) {
            (new Timer() {
                @Override
                public void run() {
                    handleWhenCSSLoaded(jsonText, json);
                }
            }).schedule(50);
            VConsole.log("Assuming CSS loading is not complete, "
                    + "postponing render phase. "
                    + "(.v-loading-indicator height == 0)");
            cssWaits++;
        } else {
            cssLoaded = true;
            handleReceivedJSONMessage(new Date(), jsonText, json);
            if (cssWaits >= MAX_CSS_WAITS) {
                VConsole.error("CSS files may have not loaded properly.");
            }
        }
    }

    /**
     * Checks whether or not the CSS is loaded. By default checks the size of
     * the loading indicator element.
     * 
     * @return
     */
    protected boolean isCSSLoaded() {
        return cssLoaded
                || getLoadingIndicator().getElement().getOffsetHeight() != 0;
    }

    /**
     * Shows the communication error notification.
     * 
     * @param details
     *            Optional details for debugging.
     * @param statusCode
     *            The status code returned for the request
     * 
     */
    protected void showCommunicationError(String details, int statusCode) {
        VConsole.error("Communication error: " + details);
        showError(details, configuration.getCommunicationError());
    }

    /**
     * Shows the authentication error notification.
     * 
     * @param details
     *            Optional details for debugging.
     */
    protected void showAuthenticationError(String details) {
        VConsole.error("Authentication error: " + details);
        showError(details, configuration.getAuthorizationError());
    }

    /**
     * Shows the session expiration notification.
     * 
     * @param details
     *            Optional details for debugging.
     */
    public void showSessionExpiredError(String details) {
        VConsole.error("Session expired: " + details);
        showError(details, configuration.getSessionExpiredError());
    }

    /**
     * Shows an error notification.
     * 
     * @param details
     *            Optional details for debugging.
     * @param message
     *            An ErrorMessage describing the error.
     */
    protected void showError(String details, ErrorMessage message) {
        showError(details, message.getCaption(), message.getMessage(),
                message.getUrl());
    }

    /**
     * Shows the error notification.
     * 
     * @param details
     *            Optional details for debugging.
     */
    private void showError(String details, String caption, String message,
            String url) {

        StringBuilder html = new StringBuilder();
        if (caption != null) {
            html.append("<h1>");
            html.append(caption);
            html.append("</h1>");
        }
        if (message != null) {
            html.append("<p>");
            html.append(message);
            html.append("</p>");
        }

        if (html.length() > 0) {

            // Add error description
            if (details != null) {
                html.append("<p><i style=\"font-size:0.7em\">");
                html.append(details);
                html.append("</i></p>");
            }

            VNotification n = VNotification.createNotification(1000 * 60 * 45,
                    uIConnector.getWidget());
            n.addEventListener(new NotificationRedirect(url));
            n.show(html.toString(), VNotification.CENTERED_TOP,
                    VNotification.STYLE_SYSTEM);
        } else {
            redirect(url);
        }
    }

    protected void startRequest() {
        if (hasActiveRequest) {
            VConsole.error("Trying to start a new request while another is active");
        }
        hasActiveRequest = true;
        requestStartTime = new Date();
        loadingIndicator.trigger();
        eventBus.fireEvent(new RequestStartingEvent(this));
    }

    protected void endRequest() {
        if (!hasActiveRequest) {
            VConsole.error("No active request");
        }
        // After checkForPendingVariableBursts() there may be a new active
        // request, so we must set hasActiveRequest to false before, not after,
        // the call. Active requests used to be tracked with an integer counter,
        // so setting it after used to work but not with the #8505 changes.
        hasActiveRequest = false;

        retryCanceledActiveRequest = false;
        webkitMaybeIgnoringRequests = false;

        if (applicationRunning) {
            checkForPendingVariableBursts();
            runPostRequestHooks(configuration.getRootPanelId());
        }

        // deferring to avoid flickering
        Scheduler.get().scheduleDeferred(new Command() {
            @Override
            public void execute() {
                if (!hasActiveRequest()) {
                    getLoadingIndicator().hide();

                    // If on Liferay and session expiration management is in
                    // use, extend session duration on each request.
                    // Doing it here rather than before the request to improve
                    // responsiveness.
                    // Postponed until the end of the next request if other
                    // requests still pending.
                    extendLiferaySession();
                }
            }
        });
        eventBus.fireEvent(new ResponseHandlingEndedEvent(this));
    }

    /**
     * This method is called after applying uidl change set to application.
     * 
     * It will clean current and queued variable change sets. And send next
     * change set if it exists.
     */
    private void checkForPendingVariableBursts() {
        cleanVariableBurst(pendingInvocations);
        if (pendingBursts.size() > 0) {
            for (LinkedHashMap<String, MethodInvocation> pendingBurst : pendingBursts) {
                cleanVariableBurst(pendingBurst);
            }
            LinkedHashMap<String, MethodInvocation> nextBurst = pendingBursts
                    .remove(0);
            buildAndSendVariableBurst(nextBurst);
        }
    }

    /**
     * Cleans given queue of variable changes of such changes that came from
     * components that do not exist anymore.
     * 
     * @param variableBurst
     */
    private void cleanVariableBurst(
            LinkedHashMap<String, MethodInvocation> variableBurst) {
        Iterator<MethodInvocation> iterator = variableBurst.values().iterator();
        while (iterator.hasNext()) {
            String id = iterator.next().getConnectorId();
            if (!getConnectorMap().hasConnector(id)
                    && !getConnectorMap().isDragAndDropPaintable(id)) {
                // variable owner does not exist anymore
                iterator.remove();
                VConsole.log("Removed variable from removed component: " + id);
            }
        }
    }

    /**
     * Checks if deferred commands are (potentially) still being executed as a
     * result of an update from the server. Returns true if a deferred command
     * might still be executing, false otherwise. This will not work correctly
     * if a deferred command is added in another deferred command.
     * <p>
     * Used by the native "client.isActive" function.
     * </p>
     * 
     * @return true if deferred commands are (potentially) being executed, false
     *         otherwise
     */
    private boolean isExecutingDeferredCommands() {
        Scheduler s = Scheduler.get();
        if (s instanceof VSchedulerImpl) {
            return ((VSchedulerImpl) s).hasWorkQueued();
        } else {
            return false;
        }
    }

    /**
     * Returns the loading indicator used by this ApplicationConnection
     * 
     * @return The loading indicator for this ApplicationConnection
     */
    public VLoadingIndicator getLoadingIndicator() {
        return loadingIndicator;
    }

    /**
     * Determines whether or not the loading indicator is showing.
     * 
     * @return true if the loading indicator is visible
     * @deprecated As of 7.1. Use {@link #getLoadingIndicator()} and
     *             {@link VLoadingIndicator#isVisible()}.isVisible() instead.
     */
    @Deprecated
    public boolean isLoadingIndicatorVisible() {
        return getLoadingIndicator().isVisible();
    }

    private static native ValueMap parseJSONResponse(String jsonText)
    /*-{
    	try {
    		return JSON.parse(jsonText);
    	} catch (ignored) {
    		return eval('(' + jsonText + ')');
    	}
    }-*/;

    private void handleReceivedJSONMessage(Date start, String jsonText,
            ValueMap json) {
        handleUIDLMessage(start, jsonText, json);
    }

    /**
     * Gets the id of the last received response. This id can be used by
     * connectors to determine whether new data has been received from the
     * server to avoid doing the same calculations multiple times.
     * <p>
     * No guarantees are made for the structure of the id other than that there
     * will be a new unique value every time a new response with data from the
     * server is received.
     * <p>
     * The initial id when no request has yet been processed is -1.
     * 
     * @return and id identifying the response
     */
    public int getLastResponseId() {
        return lastResponseId;
    }

    protected void handleUIDLMessage(final Date start, final String jsonText,
            final ValueMap json) {
        if (!responseHandlingLocks.isEmpty()) {
            // Some component is doing something that can't be interrupted
            // (e.g. animation that should be smooth). Enqueue the UIDL
            // message for later processing.
            VConsole.log("Postponing UIDL handling due to lock...");
            pendingUIDLMessages.add(new PendingUIDLMessage(start, jsonText,
                    json));
            forceHandleMessage.schedule(MAX_SUSPENDED_TIMEOUT);
            return;
        }

        /*
         * Lock response handling to avoid a situation where something pushed
         * from the server gets processed while waiting for e.g. lazily loaded
         * connectors that are needed for processing the current message.
         */
        final Object lock = new Object();
        suspendReponseHandling(lock);

        VConsole.log("Handling message from server");
        eventBus.fireEvent(new ResponseHandlingStartedEvent(this));

        // Handle redirect
        if (json.containsKey("redirect")) {
            String url = json.getValueMap("redirect").getString("url");
            VConsole.log("redirecting to " + url);
            redirect(url);
            return;
        }

        lastResponseId++;

        final MultiStepDuration handleUIDLDuration = new MultiStepDuration();

        // Get security key
        if (json.containsKey(ApplicationConstants.UIDL_SECURITY_TOKEN_ID)) {
            csrfToken = json
                    .getString(ApplicationConstants.UIDL_SECURITY_TOKEN_ID);
        }
        VConsole.log(" * Handling resources from server");

        if (json.containsKey("resources")) {
            ValueMap resources = json.getValueMap("resources");
            JsArrayString keyArray = resources.getKeyArray();
            int l = keyArray.length();
            for (int i = 0; i < l; i++) {
                String key = keyArray.get(i);
                resourcesMap.put(key, resources.getAsString(key));
            }
        }
        handleUIDLDuration.logDuration(
                " * Handling resources from server completed", 10);

        VConsole.log(" * Handling type inheritance map from server");

        if (json.containsKey("typeInheritanceMap")) {
            configuration.addComponentInheritanceInfo(json
                    .getValueMap("typeInheritanceMap"));
        }
        handleUIDLDuration.logDuration(
                " * Handling type inheritance map from server completed", 10);

        VConsole.log("Handling type mappings from server");

        if (json.containsKey("typeMappings")) {
            configuration.addComponentMappings(
                    json.getValueMap("typeMappings"), widgetSet);
        }

        VConsole.log("Handling resource dependencies");
        if (json.containsKey("scriptDependencies")) {
            loadScriptDependencies(json.getJSStringArray("scriptDependencies"));
        }
        if (json.containsKey("styleDependencies")) {
            loadStyleDependencies(json.getJSStringArray("styleDependencies"));
        }

        handleUIDLDuration.logDuration(
                " * Handling type mappings from server completed", 10);
        /*
         * Hook for e.g. TestBench to get details about server peformance
         */
        if (json.containsKey("timings")) {
            serverTimingInfo = json.getValueMap("timings");
        }

        Command c = new Command() {
            @Override
            public void execute() {
                handleUIDLDuration.logDuration(" * Loading widgets completed",
                        10);

                Profiler.enter("Handling meta information");
                ValueMap meta = null;
                if (json.containsKey("meta")) {
                    VConsole.log(" * Handling meta information");
                    meta = json.getValueMap("meta");
                    if (meta.containsKey("repaintAll")) {
                        prepareRepaintAll();
                    }
                    if (meta.containsKey("timedRedirect")) {
                        final ValueMap timedRedirect = meta
                                .getValueMap("timedRedirect");
                        redirectTimer = new Timer() {
                            @Override
                            public void run() {
                                redirect(timedRedirect.getString("url"));
                            }
                        };
                        sessionExpirationInterval = timedRedirect
                                .getInt("interval");
                    }
                }
                Profiler.leave("Handling meta information");

                if (redirectTimer != null) {
                    redirectTimer.schedule(1000 * sessionExpirationInterval);
                }

                double processUidlStart = Duration.currentTimeMillis();

                // Ensure that all connectors that we are about to update exist
                JsArrayString createdConnectorIds = createConnectorsIfNeeded(json);

                // Update states, do not fire events
                JsArrayObject<StateChangeEvent> pendingStateChangeEvents = updateConnectorState(
                        json, createdConnectorIds);

                /*
                 * Doing this here so that locales are available also to the
                 * connectors which get a state change event before the UI.
                 */
                Profiler.enter("Handling locales");
                VConsole.log(" * Handling locales");
                // Store locale data
                LocaleService
                        .addLocales(getUIConnector().getState().localeServiceState.localeData);
                Profiler.leave("Handling locales");

                // Update hierarchy, do not fire events
                ConnectorHierarchyUpdateResult connectorHierarchyUpdateResult = updateConnectorHierarchy(json);

                // Fire hierarchy change events
                sendHierarchyChangeEvents(connectorHierarchyUpdateResult.events);

                updateCaptions(pendingStateChangeEvents,
                        connectorHierarchyUpdateResult.parentChangedIds);

                delegateToWidget(pendingStateChangeEvents);

                // Fire state change events.
                sendStateChangeEvents(pendingStateChangeEvents);

                // Update of legacy (UIDL) style connectors
                updateVaadin6StyleConnectors(json);

                // Handle any RPC invocations done on the server side
                handleRpcInvocations(json);

                if (json.containsKey("dd")) {
                    // response contains data for drag and drop service
                    VDragAndDropManager.get().handleServerResponse(
                            json.getValueMap("dd"));
                }

                unregisterRemovedConnectors();

                VConsole.log("handleUIDLMessage: "
                        + (Duration.currentTimeMillis() - processUidlStart)
                        + " ms");

                Profiler.enter("Layout processing");
                try {
                    LayoutManager layoutManager = getLayoutManager();
                    layoutManager.setEverythingNeedsMeasure();
                    layoutManager.layoutNow();
                } catch (final Throwable e) {
                    VConsole.error(e);
                }
                Profiler.leave("Layout processing");

                if (ApplicationConfiguration.isDebugMode()) {
                    Profiler.enter("Dumping state changes to the console");
                    VConsole.log(" * Dumping state changes to the console");
                    VConsole.dirUIDL(json, ApplicationConnection.this);
                    Profiler.leave("Dumping state changes to the console");
                }

                if (meta != null) {
                    Profiler.enter("Error handling");
                    if (meta.containsKey("appError")) {
                        ValueMap error = meta.getValueMap("appError");

                        showError(null, error.getString("caption"),
                                error.getString("message"),
                                error.getString("url"));

                        applicationRunning = false;
                    }
                    Profiler.leave("Error handling");
                }

                // TODO build profiling for widget impl loading time

                lastProcessingTime = (int) ((new Date().getTime()) - start
                        .getTime());
                totalProcessingTime += lastProcessingTime;
                if (bootstrapTime == 0) {
                    bootstrapTime = calculateBootstrapTime();
                    if (Profiler.isEnabled() && bootstrapTime != -1) {
                        Profiler.logBootstrapTimings();
                    }
                }

                VConsole.log(" Processing time was "
                        + String.valueOf(lastProcessingTime) + "ms for "
                        + jsonText.length() + " characters of JSON");
                VConsole.log("Referenced paintables: " + connectorMap.size());

                if (meta == null || !meta.containsKey("async")) {
                    // End the request if the received message was a response,
                    // not sent asynchronously
                    endRequest();
                }
                resumeResponseHandling(lock);

                if (Profiler.isEnabled()) {
                    Scheduler.get().scheduleDeferred(new ScheduledCommand() {
                        @Override
                        public void execute() {
                            Profiler.logTimings();
                            Profiler.reset();
                        }
                    });
                }
            }

            /**
             * Properly clean up any old stuff to ensure everything is properly
             * reinitialized.
             */
            private void prepareRepaintAll() {
                String uiConnectorId = uIConnector.getConnectorId();
                if (uiConnectorId == null) {
                    // Nothing to clear yet
                    return;
                }

                // Create fake server response that says that the uiConnector
                // has no children
                JSONObject fakeHierarchy = new JSONObject();
                fakeHierarchy.put(uiConnectorId, new JSONArray());
                JSONObject fakeJson = new JSONObject();
                fakeJson.put("hierarchy", fakeHierarchy);
                ValueMap fakeValueMap = fakeJson.getJavaScriptObject().cast();

                // Update hierarchy based on the fake response
                ConnectorHierarchyUpdateResult connectorHierarchyUpdateResult = updateConnectorHierarchy(fakeValueMap);

                // Send hierarchy events based on the fake update
                sendHierarchyChangeEvents(connectorHierarchyUpdateResult.events);

                // Unregister all the old connectors that have now been removed
                unregisterRemovedConnectors();

                getLayoutManager().cleanMeasuredSizes();
            }

            private void updateCaptions(
                    JsArrayObject<StateChangeEvent> pendingStateChangeEvents,
                    FastStringSet parentChangedIds) {
                Profiler.enter("updateCaptions");

                /*
                 * Find all components that might need a caption update based on
                 * pending state and hierarchy changes
                 */
                FastStringSet needsCaptionUpdate = FastStringSet.create();
                needsCaptionUpdate.addAll(parentChangedIds);

                // Find components with potentially changed caption state
                int size = pendingStateChangeEvents.size();
                for (int i = 0; i < size; i++) {
                    StateChangeEvent event = pendingStateChangeEvents.get(i);
                    if (VCaption.mightChange(event)) {
                        ServerConnector connector = event.getConnector();
                        needsCaptionUpdate.add(connector.getConnectorId());
                    }
                }

                ConnectorMap connectorMap = getConnectorMap();

                // Update captions for all suitable candidates
                JsArrayString dump = needsCaptionUpdate.dump();
                int needsUpdateLength = dump.length();
                for (int i = 0; i < needsUpdateLength; i++) {
                    String childId = dump.get(i);
                    ServerConnector child = connectorMap.getConnector(childId);
                    if (child instanceof ComponentConnector
                            && ((ComponentConnector) child)
                                    .delegateCaptionHandling()) {
                        ServerConnector parent = child.getParent();
                        if (parent instanceof HasComponentsConnector) {
                            Profiler.enter("HasComponentsConnector.updateCaption");
                            ((HasComponentsConnector) parent)
                                    .updateCaption((ComponentConnector) child);
                            Profiler.leave("HasComponentsConnector.updateCaption");
                        }
                    }
                }

                Profiler.leave("updateCaptions");
            }

            private void delegateToWidget(
                    JsArrayObject<StateChangeEvent> pendingStateChangeEvents) {
                Profiler.enter("@DelegateToWidget");

                VConsole.log(" * Running @DelegateToWidget");

                // Keep track of types that have no @DelegateToWidget in their
                // state to optimize performance
                FastStringSet noOpTypes = FastStringSet.create();

                int size = pendingStateChangeEvents.size();
                for (int eventIndex = 0; eventIndex < size; eventIndex++) {
                    StateChangeEvent sce = pendingStateChangeEvents
                            .get(eventIndex);
                    ServerConnector connector = sce.getConnector();
                    if (connector instanceof ComponentConnector) {
                        String className = connector.getClass().getName();
                        if (noOpTypes.contains(className)) {
                            continue;
                        }
                        ComponentConnector component = (ComponentConnector) connector;

                        Type stateType = AbstractConnector
                                .getStateType(component);
                        JsArrayString delegateToWidgetProperties = stateType
                                .getDelegateToWidgetProperties();
                        if (delegateToWidgetProperties == null) {
                            noOpTypes.add(className);
                            continue;
                        }

                        int length = delegateToWidgetProperties.length();
                        for (int i = 0; i < length; i++) {
                            String propertyName = delegateToWidgetProperties
                                    .get(i);
                            if (sce.hasPropertyChanged(propertyName)) {
                                Property property = stateType
                                        .getProperty(propertyName);
                                String method = property
                                        .getDelegateToWidgetMethodName();
                                Profiler.enter("doDelegateToWidget");
                                doDelegateToWidget(component, property, method);
                                Profiler.leave("doDelegateToWidget");
                            }
                        }

                    }
                }

                Profiler.leave("@DelegateToWidget");
            }

            private void doDelegateToWidget(ComponentConnector component,
                    Property property, String methodName) {
                Type type = TypeData.getType(component.getClass());
                try {
                    Type widgetType = type.getMethod("getWidget")
                            .getReturnType();
                    Widget widget = component.getWidget();

                    Object propertyValue = property.getValue(component
                            .getState());

                    widgetType.getMethod(methodName).invoke(widget,
                            propertyValue);
                } catch (NoDataException e) {
                    throw new RuntimeException(
                            "Missing data needed to invoke @DelegateToWidget for "
                                    + Util.getSimpleName(component), e);
                }
            }

            /**
             * Sends the state change events created while updating the state
             * information.
             * 
             * This must be called after hierarchy change listeners have been
             * called. At least caption updates for the parent are strange if
             * fired from state change listeners and thus calls the parent
             * BEFORE the parent is aware of the child (through a
             * ConnectorHierarchyChangedEvent)
             * 
             * @param pendingStateChangeEvents
             *            The events to send
             */
            private void sendStateChangeEvents(
                    JsArrayObject<StateChangeEvent> pendingStateChangeEvents) {
                Profiler.enter("sendStateChangeEvents");
                VConsole.log(" * Sending state change events");

                int size = pendingStateChangeEvents.size();
                for (int i = 0; i < size; i++) {
                    StateChangeEvent sce = pendingStateChangeEvents.get(i);
                    try {
                        sce.getConnector().fireEvent(sce);
                    } catch (final Throwable e) {
                        VConsole.error(e);
                    }
                }

                Profiler.leave("sendStateChangeEvents");
            }

            private void unregisterRemovedConnectors() {
                Profiler.enter("unregisterRemovedConnectors");

                int unregistered = 0;
                JsArrayObject<ServerConnector> currentConnectors = connectorMap
                        .getConnectorsAsJsArray();
                int size = currentConnectors.size();
                for (int i = 0; i < size; i++) {
                    ServerConnector c = currentConnectors.get(i);
                    if (c.getParent() != null) {
                        // only do this check if debug mode is active
                        if (ApplicationConfiguration.isDebugMode()) {
                            Profiler.enter("unregisterRemovedConnectors check parent - this is only performed in debug mode");
                            // this is slow for large layouts, 25-30% of total
                            // time for some operations even on modern browsers
                            if (!c.getParent().getChildren().contains(c)) {
                                VConsole.error("ERROR: Connector is connected to a parent but the parent does not contain the connector");
                            }
                            Profiler.leave("unregisterRemovedConnectors check parent - this is only performed in debug mode");
                        }
                    } else if (c == getUIConnector()) {
                        // UIConnector for this connection, leave as-is
                    } else if (c instanceof WindowConnector
                            && getUIConnector().hasSubWindow(
                                    (WindowConnector) c)) {
                        // Sub window attached to this UIConnector, leave
                        // as-is
                    } else {
                        // The connector has been detached from the
                        // hierarchy, unregister it and any possible
                        // children. The UIConnector should never be
                        // unregistered even though it has no parent.
                        Profiler.enter("unregisterRemovedConnectors unregisterConnector");
                        connectorMap.unregisterConnector(c);
                        Profiler.leave("unregisterRemovedConnectors unregisterConnector");
                        unregistered++;
                    }

                }

                VConsole.log("* Unregistered " + unregistered + " connectors");
                Profiler.leave("unregisterRemovedConnectors");
            }

            private JsArrayString createConnectorsIfNeeded(ValueMap json) {
                VConsole.log(" * Creating connectors (if needed)");

                JsArrayString createdConnectors = JavaScriptObject
                        .createArray().cast();
                if (!json.containsKey("types")) {
                    return createdConnectors;
                }

                Profiler.enter("Creating connectors");

                ValueMap types = json.getValueMap("types");
                JsArrayString keyArray = types.getKeyArray();
                for (int i = 0; i < keyArray.length(); i++) {
                    try {
                        String connectorId = keyArray.get(i);
                        ServerConnector connector = connectorMap
                                .getConnector(connectorId);
                        if (connector != null) {
                            continue;
                        }
                        int connectorType = Integer.parseInt(types
                                .getString(connectorId));

                        Class<? extends ServerConnector> connectorClass = configuration
                                .getConnectorClassByEncodedTag(connectorType);

                        // Connector does not exist so we must create it
                        if (connectorClass != uIConnector.getClass()) {
                            // create, initialize and register the paintable
                            Profiler.enter("ApplicationConnection.getConnector");
                            connector = getConnector(connectorId, connectorType);
                            Profiler.leave("ApplicationConnection.getConnector");

                            createdConnectors.push(connectorId);
                        } else {
                            // First UIConnector update. Before this the
                            // UIConnector has been created but not
                            // initialized as the connector id has not been
                            // known
                            connectorMap.registerConnector(connectorId,
                                    uIConnector);
                            uIConnector.doInit(connectorId,
                                    ApplicationConnection.this);
                            createdConnectors.push(connectorId);
                        }
                    } catch (final Throwable e) {
                        VConsole.error(e);
                    }
                }

                Profiler.leave("Creating connectors");

                return createdConnectors;
            }

            private void updateVaadin6StyleConnectors(ValueMap json) {
                Profiler.enter("updateVaadin6StyleConnectors");

                JsArray<ValueMap> changes = json.getJSValueMapArray("changes");
                int length = changes.length();

                VConsole.log(" * Passing UIDL to Vaadin 6 style connectors");
                // update paintables
                for (int i = 0; i < length; i++) {
                    try {
                        final UIDL change = changes.get(i).cast();
                        final UIDL uidl = change.getChildUIDL(0);
                        String connectorId = uidl.getId();

                        final ComponentConnector legacyConnector = (ComponentConnector) connectorMap
                                .getConnector(connectorId);
                        if (legacyConnector instanceof Paintable) {
                            String key = null;
                            if (Profiler.isEnabled()) {
                                key = "updateFromUIDL for "
                                        + Util.getSimpleName(legacyConnector);
                                Profiler.enter(key);
                            }

                            ((Paintable) legacyConnector).updateFromUIDL(uidl,
                                    ApplicationConnection.this);

                            if (Profiler.isEnabled()) {
                                Profiler.leave(key);
                            }
                        } else if (legacyConnector == null) {
                            VConsole.error("Received update for "
                                    + uidl.getTag()
                                    + ", but there is no such paintable ("
                                    + connectorId + ") rendered.");
                        } else {
                            VConsole.error("Server sent Vaadin 6 style updates for "
                                    + Util.getConnectorString(legacyConnector)
                                    + " but this is not a Vaadin 6 Paintable");
                        }

                    } catch (final Throwable e) {
                        VConsole.error(e);
                    }
                }

                Profiler.leave("updateVaadin6StyleConnectors");
            }

            private void sendHierarchyChangeEvents(
                    JsArrayObject<ConnectorHierarchyChangeEvent> events) {
                int eventCount = events.size();
                if (eventCount == 0) {
                    return;
                }
                Profiler.enter("sendHierarchyChangeEvents");

                VConsole.log(" * Sending hierarchy change events");
                for (int i = 0; i < eventCount; i++) {
                    ConnectorHierarchyChangeEvent event = events.get(i);
                    try {
                        logHierarchyChange(event);
                        event.getConnector().fireEvent(event);
                    } catch (final Throwable e) {
                        VConsole.error(e);
                    }
                }

                Profiler.leave("sendHierarchyChangeEvents");
            }

            private void logHierarchyChange(ConnectorHierarchyChangeEvent event) {
                if (true) {
                    // Always disabled for now. Can be enabled manually
                    return;
                }

                VConsole.log("Hierarchy changed for "
                        + Util.getConnectorString(event.getConnector()));
                String oldChildren = "* Old children: ";
                for (ComponentConnector child : event.getOldChildren()) {
                    oldChildren += Util.getConnectorString(child) + " ";
                }
                VConsole.log(oldChildren);

                String newChildren = "* New children: ";
                HasComponentsConnector parent = (HasComponentsConnector) event
                        .getConnector();
                for (ComponentConnector child : parent.getChildComponents()) {
                    newChildren += Util.getConnectorString(child) + " ";
                }
                VConsole.log(newChildren);
            }

            private JsArrayObject<StateChangeEvent> updateConnectorState(
                    ValueMap json, JsArrayString createdConnectorIds) {
                JsArrayObject<StateChangeEvent> events = JavaScriptObject
                        .createArray().cast();
                VConsole.log(" * Updating connector states");
                if (!json.containsKey("state")) {
                    return events;
                }

                Profiler.enter("updateConnectorState");

                FastStringSet remainingNewConnectors = FastStringSet.create();
                remainingNewConnectors.addAll(createdConnectorIds);

                // set states for all paintables mentioned in "state"
                ValueMap states = json.getValueMap("state");
                JsArrayString keyArray = states.getKeyArray();
                for (int i = 0; i < keyArray.length(); i++) {
                    try {
                        String connectorId = keyArray.get(i);
                        ServerConnector connector = connectorMap
                                .getConnector(connectorId);
                        if (null != connector) {
                            Profiler.enter("updateConnectorState inner loop");
                            if (Profiler.isEnabled()) {
                                Profiler.enter("Decode connector state "
                                        + Util.getSimpleName(connector));
                            }

                            JSONObject stateJson = new JSONObject(
                                    states.getJavaScriptObject(connectorId));

                            if (connector instanceof HasJavaScriptConnectorHelper) {
                                ((HasJavaScriptConnectorHelper) connector)
                                        .getJavascriptConnectorHelper()
                                        .setNativeState(
                                                stateJson.getJavaScriptObject());
                            }

                            SharedState state = connector.getState();

                            Profiler.enter("updateConnectorState decodeValue");
                            JsonDecoder.decodeValue(new Type(state.getClass()
                                    .getName(), null), stateJson, state,
                                    ApplicationConnection.this);
                            Profiler.leave("updateConnectorState decodeValue");

                            if (Profiler.isEnabled()) {
                                Profiler.leave("Decode connector state "
                                        + Util.getSimpleName(connector));
                            }

                            Profiler.enter("updateConnectorState create event");

                            boolean isNewConnector = remainingNewConnectors
                                    .contains(connectorId);
                            if (isNewConnector) {
                                remainingNewConnectors.remove(connectorId);
                            }

                            StateChangeEvent event = new StateChangeEvent(
                                    connector, stateJson, isNewConnector);
                            events.add(event);
                            Profiler.leave("updateConnectorState create event");

                            Profiler.leave("updateConnectorState inner loop");
                        }
                    } catch (final Throwable e) {
                        VConsole.error(e);
                    }
                }

                Profiler.enter("updateConnectorState newWithoutState");
                // Fire events for properties using the default value for newly
                // created connectors even if there were no state changes
                JsArrayString dump = remainingNewConnectors.dump();
                int length = dump.length();
                for (int i = 0; i < length; i++) {
                    String connectorId = dump.get(i);
                    ServerConnector connector = connectorMap
                            .getConnector(connectorId);

                    StateChangeEvent event = new StateChangeEvent(connector,
                            new JSONObject(), true);

                    events.add(event);

                }
                Profiler.leave("updateConnectorState newWithoutState");

                Profiler.leave("updateConnectorState");

                return events;
            }

            /**
             * Updates the connector hierarchy and returns a list of events that
             * should be fired after update of the hierarchy and the state is
             * done.
             * 
             * @param json
             *            The JSON containing the hierarchy information
             * @return A collection of events that should be fired when update
             *         of hierarchy and state is complete and a list of all
             *         connectors for which the parent has changed
             */
            private ConnectorHierarchyUpdateResult updateConnectorHierarchy(
                    ValueMap json) {
                ConnectorHierarchyUpdateResult result = new ConnectorHierarchyUpdateResult();

                VConsole.log(" * Updating connector hierarchy");
                if (!json.containsKey("hierarchy")) {
                    return result;
                }

                Profiler.enter("updateConnectorHierarchy");

                FastStringSet maybeDetached = FastStringSet.create();

                ValueMap hierarchies = json.getValueMap("hierarchy");
                JsArrayString hierarchyKeys = hierarchies.getKeyArray();
                for (int i = 0; i < hierarchyKeys.length(); i++) {
                    try {
                        Profiler.enter("updateConnectorHierarchy hierarchy entry");

                        String connectorId = hierarchyKeys.get(i);
                        ServerConnector parentConnector = connectorMap
                                .getConnector(connectorId);
                        JsArrayString childConnectorIds = hierarchies
                                .getJSStringArray(connectorId);
                        int childConnectorSize = childConnectorIds.length();

                        Profiler.enter("updateConnectorHierarchy find new connectors");

                        List<ServerConnector> newChildren = new ArrayList<ServerConnector>();
                        List<ComponentConnector> newComponents = new ArrayList<ComponentConnector>();
                        for (int connectorIndex = 0; connectorIndex < childConnectorSize; connectorIndex++) {
                            String childConnectorId = childConnectorIds
                                    .get(connectorIndex);
                            ServerConnector childConnector = connectorMap
                                    .getConnector(childConnectorId);
                            if (childConnector == null) {
                                VConsole.error("Hierarchy claims that "
                                        + childConnectorId
                                        + " is a child for "
                                        + connectorId
                                        + " ("
                                        + parentConnector.getClass().getName()
                                        + ") but no connector with id "
                                        + childConnectorId
                                        + " has been registered. "
                                        + "More information might be available in the server-side log if assertions are enabled");
                                continue;
                            }
                            newChildren.add(childConnector);
                            if (childConnector instanceof ComponentConnector) {
                                newComponents
                                        .add((ComponentConnector) childConnector);
                            } else if (!(childConnector instanceof AbstractExtensionConnector)) {
                                throw new IllegalStateException(
                                        Util.getConnectorString(childConnector)
                                                + " is not a ComponentConnector nor an AbstractExtensionConnector");
                            }
                            if (childConnector.getParent() != parentConnector) {
                                childConnector.setParent(parentConnector);
                                result.parentChangedIds.add(childConnectorId);
                                // Not detached even if previously removed from
                                // parent
                                maybeDetached.remove(childConnectorId);
                            }
                        }

                        Profiler.leave("updateConnectorHierarchy find new connectors");

                        // TODO This check should be done on the server side in
                        // the future so the hierarchy update is only sent when
                        // something actually has changed
                        List<ServerConnector> oldChildren = parentConnector
                                .getChildren();
                        boolean actuallyChanged = !Util.collectionsEquals(
                                oldChildren, newChildren);

                        if (!actuallyChanged) {
                            continue;
                        }

                        Profiler.enter("updateConnectorHierarchy handle HasComponentsConnector");

                        if (parentConnector instanceof HasComponentsConnector) {
                            HasComponentsConnector ccc = (HasComponentsConnector) parentConnector;
                            List<ComponentConnector> oldComponents = ccc
                                    .getChildComponents();
                            if (!Util.collectionsEquals(oldComponents,
                                    newComponents)) {
                                // Fire change event if the hierarchy has
                                // changed
                                ConnectorHierarchyChangeEvent event = GWT
                                        .create(ConnectorHierarchyChangeEvent.class);
                                event.setOldChildren(oldComponents);
                                event.setConnector(parentConnector);
                                ccc.setChildComponents(newComponents);
                                result.events.add(event);
                            }
                        } else if (!newComponents.isEmpty()) {
                            VConsole.error("Hierachy claims "
                                    + Util.getConnectorString(parentConnector)
                                    + " has component children even though it isn't a HasComponentsConnector");
                        }

                        Profiler.leave("updateConnectorHierarchy handle HasComponentsConnector");

                        Profiler.enter("updateConnectorHierarchy setChildren");
                        parentConnector.setChildren(newChildren);
                        Profiler.leave("updateConnectorHierarchy setChildren");

                        Profiler.enter("updateConnectorHierarchy find removed children");

                        /*
                         * Find children removed from this parent and mark for
                         * removal unless they are already attached to some
                         * other parent.
                         */
                        for (ServerConnector oldChild : oldChildren) {
                            if (oldChild.getParent() != parentConnector) {
                                // Ignore if moved to some other connector
                                continue;
                            }

                            if (!newChildren.contains(oldChild)) {
                                /*
                                 * Consider child detached for now, will be
                                 * cleared if it is later on added to some other
                                 * parent.
                                 */
                                maybeDetached.add(oldChild.getConnectorId());
                            }
                        }

                        Profiler.leave("updateConnectorHierarchy find removed children");
                    } catch (final Throwable e) {
                        VConsole.error(e);
                    } finally {
                        Profiler.leave("updateConnectorHierarchy hierarchy entry");
                    }
                }

                Profiler.enter("updateConnectorHierarchy detach removed connectors");

                /*
                 * Connector is in maybeDetached at this point if it has been
                 * removed from its parent but not added to any other parent
                 */
                JsArrayString maybeDetachedArray = maybeDetached.dump();
                for (int i = 0; i < maybeDetachedArray.length(); i++) {
                    ServerConnector removed = connectorMap
                            .getConnector(maybeDetachedArray.get(i));
                    recursivelyDetach(removed, result.events);
                }

                Profiler.leave("updateConnectorHierarchy detach removed connectors");

                Profiler.leave("updateConnectorHierarchy");

                return result;

            }

            private void recursivelyDetach(ServerConnector connector,
                    JsArrayObject<ConnectorHierarchyChangeEvent> events) {

                /*
                 * Reset state in an attempt to keep it consistent with the
                 * hierarchy. No children and no parent is the initial situation
                 * for the hierarchy, so changing the state to its initial value
                 * is the closest we can get without data from the server.
                 * #10151
                 */
                Profiler.enter("ApplicationConnection recursivelyDetach reset state");
                try {
                    Profiler.enter("ApplicationConnection recursivelyDetach reset state - getStateType");
                    Type stateType = AbstractConnector.getStateType(connector);
                    Profiler.leave("ApplicationConnection recursivelyDetach reset state - getStateType");

                    // Empty state instance to get default property values from
                    Profiler.enter("ApplicationConnection recursivelyDetach reset state - createInstance");
                    Object defaultState = stateType.createInstance();
                    Profiler.leave("ApplicationConnection recursivelyDetach reset state - createInstance");

                    if (connector instanceof AbstractConnector) {
                        // optimization as the loop setting properties is very
                        // slow, especially on IE8
                        replaceState((AbstractConnector) connector,
                                defaultState);
                    } else {
                        SharedState state = connector.getState();

                        Profiler.enter("ApplicationConnection recursivelyDetach reset state - properties");
                        JsArrayObject<Property> properties = stateType
                                .getPropertiesAsArray();
                        int size = properties.size();
                        for (int i = 0; i < size; i++) {
                            Property property = properties.get(i);
                            property.setValue(state,
                                    property.getValue(defaultState));
                        }
                        Profiler.leave("ApplicationConnection recursivelyDetach reset state - properties");
                    }
                } catch (NoDataException e) {
                    throw new RuntimeException("Can't reset state for "
                            + Util.getConnectorString(connector), e);
                } finally {
                    Profiler.leave("ApplicationConnection recursivelyDetach reset state");
                }

                Profiler.enter("ApplicationConnection recursivelyDetach perform detach");
                /*
                 * Recursively detach children to make sure they get
                 * setParent(null) and hierarchy change events as needed.
                 */
                for (ServerConnector child : connector.getChildren()) {
                    /*
                     * Server doesn't send updated child data for removed
                     * connectors -> ignore child that still seems to be a child
                     * of this connector although it has been moved to some part
                     * of the hierarchy that is not detached.
                     */
                    if (child.getParent() != connector) {
                        continue;
                    }
                    recursivelyDetach(child, events);
                }
                Profiler.leave("ApplicationConnection recursivelyDetach perform detach");

                /*
                 * Clear child list and parent
                 */
                Profiler.enter("ApplicationConnection recursivelyDetach clear children and parent");
                connector
                        .setChildren(Collections.<ServerConnector> emptyList());
                connector.setParent(null);
                Profiler.leave("ApplicationConnection recursivelyDetach clear children and parent");

                /*
                 * Create an artificial hierarchy event for containers to give
                 * it a chance to clean up after its children if it has any
                 */
                Profiler.enter("ApplicationConnection recursivelyDetach create hierarchy event");
                if (connector instanceof HasComponentsConnector) {
                    HasComponentsConnector ccc = (HasComponentsConnector) connector;
                    List<ComponentConnector> oldChildren = ccc
                            .getChildComponents();
                    if (!oldChildren.isEmpty()) {
                        /*
                         * HasComponentsConnector has a separate child component
                         * list that should also be cleared
                         */
                        ccc.setChildComponents(Collections
                                .<ComponentConnector> emptyList());

                        // Create event and add it to the list of pending events
                        ConnectorHierarchyChangeEvent event = GWT
                                .create(ConnectorHierarchyChangeEvent.class);
                        event.setConnector(connector);
                        event.setOldChildren(oldChildren);
                        events.add(event);
                    }
                }
                Profiler.leave("ApplicationConnection recursivelyDetach create hierarchy event");
            }

            private native void replaceState(AbstractConnector connector,
                    Object defaultState)
            /*-{
                connector.@com.vaadin.client.ui.AbstractConnector::state = defaultState;
            }-*/;

            private void handleRpcInvocations(ValueMap json) {
                if (json.containsKey("rpc")) {
                    Profiler.enter("handleRpcInvocations");

                    VConsole.log(" * Performing server to client RPC calls");

                    JSONArray rpcCalls = new JSONArray(
                            json.getJavaScriptObject("rpc"));

                    int rpcLength = rpcCalls.size();
                    for (int i = 0; i < rpcLength; i++) {
                        try {
                            JSONArray rpcCall = (JSONArray) rpcCalls.get(i);
                            rpcManager.parseAndApplyInvocation(rpcCall,
                                    ApplicationConnection.this);
                        } catch (final Throwable e) {
                            VConsole.error(e);
                        }
                    }

                    Profiler.leave("handleRpcInvocations");
                }
            }

        };
        ApplicationConfiguration.runWhenDependenciesLoaded(c);
    }

    private void loadStyleDependencies(JsArrayString dependencies) {
        // Assuming no reason to interpret in a defined order
        ResourceLoadListener resourceLoadListener = new ResourceLoadListener() {
            @Override
            public void onLoad(ResourceLoadEvent event) {
                ApplicationConfiguration.endDependencyLoading();
            }

            @Override
            public void onError(ResourceLoadEvent event) {
                VConsole.error(event.getResourceUrl()
                        + " could not be loaded, or the load detection failed because the stylesheet is empty.");
                // The show must go on
                onLoad(event);
            }
        };
        ResourceLoader loader = ResourceLoader.get();
        for (int i = 0; i < dependencies.length(); i++) {
            String url = translateVaadinUri(dependencies.get(i));
            ApplicationConfiguration.startDependencyLoading();
            loader.loadStylesheet(url, resourceLoadListener);
        }
    }

    private void loadScriptDependencies(final JsArrayString dependencies) {
        if (dependencies.length() == 0) {
            return;
        }

        // Listener that loads the next when one is completed
        ResourceLoadListener resourceLoadListener = new ResourceLoadListener() {
            @Override
            public void onLoad(ResourceLoadEvent event) {
                if (dependencies.length() != 0) {
                    String url = translateVaadinUri(dependencies.shift());
                    ApplicationConfiguration.startDependencyLoading();
                    // Load next in chain (hopefully already preloaded)
                    event.getResourceLoader().loadScript(url, this);
                }
                // Call start for next before calling end for current
                ApplicationConfiguration.endDependencyLoading();
            }

            @Override
            public void onError(ResourceLoadEvent event) {
                VConsole.error(event.getResourceUrl() + " could not be loaded.");
                // The show must go on
                onLoad(event);
            }
        };

        ResourceLoader loader = ResourceLoader.get();

        // Start chain by loading first
        String url = translateVaadinUri(dependencies.shift());
        ApplicationConfiguration.startDependencyLoading();
        loader.loadScript(url, resourceLoadListener);

        // Preload all remaining
        for (int i = 0; i < dependencies.length(); i++) {
            String preloadUrl = translateVaadinUri(dependencies.get(i));
            loader.preloadResource(preloadUrl, null);
        }
    }

    // Redirect browser, null reloads current page
    private static native void redirect(String url)
    /*-{
    	if (url) {
    		$wnd.location = url;
    	} else {
    		$wnd.location.reload(false);
    	}
    }-*/;

    private void addVariableToQueue(String connectorId, String variableName,
            Object value, boolean immediate) {
        boolean lastOnly = !immediate;
        // note that type is now deduced from value
        addMethodInvocationToQueue(new LegacyChangeVariablesInvocation(
                connectorId, variableName, value), lastOnly, lastOnly);
    }

    /**
     * Adds an explicit RPC method invocation to the send queue.
     * 
     * @since 7.0
     * 
     * @param invocation
     *            RPC method invocation
     * @param delayed
     *            <code>false</code> to trigger sending within a short time
     *            window (possibly combining subsequent calls to a single
     *            request), <code>true</code> to let the framework delay sending
     *            of RPC calls and variable changes until the next non-delayed
     *            change
     * @param lastOnly
     *            <code>true</code> to remove all previously delayed invocations
     *            of the same method that were also enqueued with lastonly set
     *            to <code>true</code>. <code>false</code> to add invocation to
     *            the end of the queue without touching previously enqueued
     *            invocations.
     */
    public void addMethodInvocationToQueue(MethodInvocation invocation,
            boolean delayed, boolean lastOnly) {
        String tag;
        if (lastOnly) {
            tag = invocation.getLastOnlyTag();
            assert !tag.matches("\\d+") : "getLastOnlyTag value must have at least one non-digit character";
            pendingInvocations.remove(tag);
        } else {
            tag = Integer.toString(lastInvocationTag++);
        }
        pendingInvocations.put(tag, invocation);
        if (!delayed) {
            sendPendingVariableChanges();
        }
    }

    /**
     * Removes any pending invocation of the given method from the queue
     * 
     * @param invocation
     *            The invocation to remove
     */
    public void removePendingInvocations(MethodInvocation invocation) {
        Iterator<MethodInvocation> iter = pendingInvocations.values()
                .iterator();
        while (iter.hasNext()) {
            MethodInvocation mi = iter.next();
            if (mi.equals(invocation)) {
                iter.remove();
            }
        }
    }

    /**
     * This method sends currently queued variable changes to server. It is
     * called when immediate variable update must happen.
     * 
     * To ensure correct order for variable changes (due servers multithreading
     * or network), we always wait for active request to be handler before
     * sending a new one. If there is an active request, we will put varible
     * "burst" to queue that will be purged after current request is handled.
     * 
     */
    public void sendPendingVariableChanges() {
        if (!deferedSendPending) {
            deferedSendPending = true;
            Scheduler.get().scheduleFinally(sendPendingCommand);
        }
    }

    private final ScheduledCommand sendPendingCommand = new ScheduledCommand() {
        @Override
        public void execute() {
            deferedSendPending = false;
            doSendPendingVariableChanges();
        }
    };
    private boolean deferedSendPending = false;

    private void doSendPendingVariableChanges() {
        if (applicationRunning) {
            if (hasActiveRequest() || (push != null && !push.isActive())) {
                // skip empty queues if there are pending bursts to be sent
                if (pendingInvocations.size() > 0 || pendingBursts.size() == 0) {
                    pendingBursts.add(pendingInvocations);
                    pendingInvocations = new LinkedHashMap<String, MethodInvocation>();
                    // Keep tag string short
                    lastInvocationTag = 0;
                }
            } else {
                buildAndSendVariableBurst(pendingInvocations);
            }
        }
    }

    /**
     * Build the variable burst and send it to server.
     * 
     * When sync is forced, we also force sending of all pending variable-bursts
     * at the same time. This is ok as we can assume that DOM will never be
     * updated after this.
     * 
     * @param pendingInvocations
     *            List of RPC method invocations to send
     */
    private void buildAndSendVariableBurst(
            LinkedHashMap<String, MethodInvocation> pendingInvocations) {
        final StringBuffer req = new StringBuffer();

        while (!pendingInvocations.isEmpty()) {
            if (ApplicationConfiguration.isDebugMode()) {
                Util.logVariableBurst(this, pendingInvocations.values());
            }

            JSONArray reqJson = new JSONArray();

            for (MethodInvocation invocation : pendingInvocations.values()) {
                JSONArray invocationJson = new JSONArray();
                invocationJson.set(0,
                        new JSONString(invocation.getConnectorId()));
                invocationJson.set(1,
                        new JSONString(invocation.getInterfaceName()));
                invocationJson.set(2,
                        new JSONString(invocation.getMethodName()));
                JSONArray paramJson = new JSONArray();

                Type[] parameterTypes = null;
                if (!isLegacyVariableChange(invocation)
                        && !isJavascriptRpc(invocation)) {
                    try {
                        Type type = new Type(invocation.getInterfaceName(),
                                null);
                        Method method = type.getMethod(invocation
                                .getMethodName());
                        parameterTypes = method.getParameterTypes();
                    } catch (NoDataException e) {
                        throw new RuntimeException("No type data for "
                                + invocation.toString(), e);
                    }
                }

                for (int i = 0; i < invocation.getParameters().length; ++i) {
                    // TODO non-static encoder?
                    Type type = null;
                    if (parameterTypes != null) {
                        type = parameterTypes[i];
                    }
                    Object value = invocation.getParameters()[i];
                    paramJson.set(i, JsonEncoder.encode(value, type, this));
                }
                invocationJson.set(3, paramJson);
                reqJson.set(reqJson.size(), invocationJson);
            }

            // escape burst separators (if any)
            req.append(escapeBurstContents(reqJson.toString()));

            pendingInvocations.clear();
            // Keep tag string short
            lastInvocationTag = 0;
        }

        // Include the browser detail parameters if they aren't already sent
        String extraParams;
        if (!getConfiguration().isBrowserDetailsSent()) {
            extraParams = getNativeBrowserDetailsParameters(getConfiguration()
                    .getRootPanelId());
            getConfiguration().setBrowserDetailsSent();
        } else {
            extraParams = "";
        }
        if (!getConfiguration().isWidgetsetVersionSent()) {
            if (!extraParams.isEmpty()) {
                extraParams += "&";
            }
            String widgetsetVersion = Version.getFullVersion();
            extraParams += "v-wsver=" + widgetsetVersion;

            getConfiguration().setWidgetsetVersionSent();
        }
        makeUidlRequest(req.toString(), extraParams);
    }

    private boolean isJavascriptRpc(MethodInvocation invocation) {
        return invocation instanceof JavaScriptMethodInvocation;
    }

    private boolean isLegacyVariableChange(MethodInvocation invocation) {
        return ApplicationConstants.UPDATE_VARIABLE_METHOD.equals(invocation
                .getInterfaceName())
                && ApplicationConstants.UPDATE_VARIABLE_METHOD
                        .equals(invocation.getMethodName());
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */
    public void updateVariable(String paintableId, String variableName,
            ServerConnector newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */

    public void updateVariable(String paintableId, String variableName,
            String newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */

    public void updateVariable(String paintableId, String variableName,
            int newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */

    public void updateVariable(String paintableId, String variableName,
            long newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */

    public void updateVariable(String paintableId, String variableName,
            float newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */

    public void updateVariable(String paintableId, String variableName,
            double newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param newValue
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */

    public void updateVariable(String paintableId, String variableName,
            boolean newValue, boolean immediate) {
        addVariableToQueue(paintableId, variableName, newValue, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * <p>
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * </p>
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param map
     *            the new values to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */
    public void updateVariable(String paintableId, String variableName,
            Map<String, Object> map, boolean immediate) {
        addVariableToQueue(paintableId, variableName, map, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * 
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update.
     * 
     * A null array is sent as an empty array.
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param values
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */
    public void updateVariable(String paintableId, String variableName,
            String[] values, boolean immediate) {
        addVariableToQueue(paintableId, variableName, values, immediate);
    }

    /**
     * Sends a new value for the given paintables given variable to the server.
     * 
     * The update is actually queued to be sent at a suitable time. If immediate
     * is true, the update is sent as soon as possible. If immediate is false,
     * the update will be sent along with the next immediate update. </p>
     * 
     * A null array is sent as an empty array.
     * 
     * 
     * @param paintableId
     *            the id of the paintable that owns the variable
     * @param variableName
     *            the name of the variable
     * @param values
     *            the new value to be sent
     * @param immediate
     *            true if the update is to be sent as soon as possible
     */
    public void updateVariable(String paintableId, String variableName,
            Object[] values, boolean immediate) {
        addVariableToQueue(paintableId, variableName, values, immediate);
    }

    /**
     * Encode burst separator characters in a String for transport over the
     * network. This protects from separator injection attacks.
     * 
     * @param value
     *            to encode
     * @return encoded value
     */
    protected String escapeBurstContents(String value) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); ++i) {
            char character = value.charAt(i);
            switch (character) {
            case VAR_ESCAPE_CHARACTER:
                // fall-through - escape character is duplicated
            case VAR_BURST_SEPARATOR:
                result.append(VAR_ESCAPE_CHARACTER);
                // encode as letters for easier reading
                result.append(((char) (character + 0x30)));
                break;
            default:
                // the char is not a special one - add it to the result as is
                result.append(character);
                break;
            }
        }
        return result.toString();
    }

    /**
     * Does absolutely nothing. Replaced by {@link LayoutManager}.
     * 
     * @param container
     * @deprecated As of 7.0, serves no purpose
     */
    @Deprecated
    public void runDescendentsLayout(HasWidgets container) {
    }

    /**
     * This will cause re-layouting of all components. Mainly used for
     * development. Published to JavaScript.
     */
    public void forceLayout() {
        Duration duration = new Duration();

        layoutManager.forceLayout();

        VConsole.log("forceLayout in " + duration.elapsedMillis() + " ms");
    }

    /**
     * Returns false
     * 
     * @param paintable
     * @return false, always
     * @deprecated As of 7.0, serves no purpose
     */
    @Deprecated
    private boolean handleComponentRelativeSize(ComponentConnector paintable) {
        return false;
    }

    /**
     * Returns false
     * 
     * @param paintable
     * @return false, always
     * @deprecated As of 7.0, serves no purpose
     */
    @Deprecated
    public boolean handleComponentRelativeSize(Widget widget) {
        return handleComponentRelativeSize(connectorMap.getConnector(widget));

    }

    @Deprecated
    public ComponentConnector getPaintable(UIDL uidl) {
        // Non-component connectors shouldn't be painted from legacy connectors
        return (ComponentConnector) getConnector(uidl.getId(),
                Integer.parseInt(uidl.getTag()));
    }

    /**
     * Get either an existing ComponentConnector or create a new
     * ComponentConnector with the given type and id.
     * 
     * If a ComponentConnector with the given id already exists, returns it.
     * Otherwise creates and registers a new ComponentConnector of the given
     * type.
     * 
     * @param connectorId
     *            Id of the paintable
     * @param connectorType
     *            Type of the connector, as passed from the server side
     * 
     * @return Either an existing ComponentConnector or a new ComponentConnector
     *         of the given type
     */
    public ServerConnector getConnector(String connectorId, int connectorType) {
        if (!connectorMap.hasConnector(connectorId)) {
            return createAndRegisterConnector(connectorId, connectorType);
        }
        return connectorMap.getConnector(connectorId);
    }

    /**
     * Creates a new ServerConnector with the given type and id.
     * 
     * Creates and registers a new ServerConnector of the given type. Should
     * never be called with the connector id of an existing connector.
     * 
     * @param connectorId
     *            Id of the new connector
     * @param connectorType
     *            Type of the connector, as passed from the server side
     * 
     * @return A new ServerConnector of the given type
     */
    private ServerConnector createAndRegisterConnector(String connectorId,
            int connectorType) {
        Profiler.enter("ApplicationConnection.createAndRegisterConnector");

        // Create and register a new connector with the given type
        ServerConnector p = widgetSet.createConnector(connectorType,
                configuration);
        connectorMap.registerConnector(connectorId, p);
        p.doInit(connectorId, this);

        Profiler.leave("ApplicationConnection.createAndRegisterConnector");
        return p;
    }

    /**
     * Gets a recource that has been pre-loaded via UIDL, such as custom
     * layouts.
     * 
     * @param name
     *            identifier of the resource to get
     * @return the resource
     */
    public String getResource(String name) {
        return resourcesMap.get(name);
    }

    /**
     * Singleton method to get instance of app's context menu.
     * 
     * @return VContextMenu object
     */
    public VContextMenu getContextMenu() {
        if (contextMenu == null) {
            contextMenu = new VContextMenu();
            contextMenu.setOwner(uIConnector.getWidget());
            DOM.setElementProperty(contextMenu.getElement(), "id",
                    "PID_VAADIN_CM");
        }
        return contextMenu;
    }

    /**
     * Translates custom protocols in UIDL URI's to be recognizable by browser.
     * All uri's from UIDL should be routed via this method before giving them
     * to browser due URI's in UIDL may contain custom protocols like theme://.
     * 
     * @param uidlUri
     *            Vaadin URI from uidl
     * @return translated URI ready for browser
     */
    public String translateVaadinUri(String uidlUri) {
        if (uidlUri == null) {
            return null;
        }
        if (uidlUri.startsWith("theme://")) {
            final String themeUri = configuration.getThemeUri();
            if (themeUri == null) {
                VConsole.error("Theme not set: ThemeResource will not be found. ("
                        + uidlUri + ")");
            }
            uidlUri = themeUri + uidlUri.substring(7);
        }

        if (uidlUri.startsWith(ApplicationConstants.PUBLISHED_PROTOCOL_PREFIX)) {
            // getAppUri *should* always end with /
            // substring *should* always start with / (published:///foo.bar
            // without published://)
            uidlUri = ApplicationConstants.APP_PROTOCOL_PREFIX
                    + ApplicationConstants.PUBLISHED_FILE_PATH
                    + uidlUri
                            .substring(ApplicationConstants.PUBLISHED_PROTOCOL_PREFIX
                                    .length());
            // Let translation of app:// urls take care of the rest
        }
        if (uidlUri.startsWith(ApplicationConstants.APP_PROTOCOL_PREFIX)) {
            String relativeUrl = uidlUri
                    .substring(ApplicationConstants.APP_PROTOCOL_PREFIX
                            .length());
            ApplicationConfiguration conf = getConfiguration();
            String serviceUrl = conf.getServiceUrl();
            if (conf.useServiceUrlPathParam()) {
                // Should put path in v-resourcePath parameter and append query
                // params to base portlet url
                String[] parts = relativeUrl.split("\\?", 2);
                String path = parts[0];

                // If there's a "?" followed by something, append it as a query
                // string to the base URL
                if (parts.length > 1) {
                    String appUrlParams = parts[1];
                    serviceUrl = addGetParameters(serviceUrl, appUrlParams);
                }
                if (!path.startsWith("/")) {
                    path = '/' + path;
                }
                String pathParam = conf.getServiceUrlParameterName() + "="
                        + URL.encodeQueryString(path);
                serviceUrl = addGetParameters(serviceUrl, pathParam);
                uidlUri = serviceUrl;
            } else {
                uidlUri = serviceUrl + relativeUrl;
            }
        }
        if (uidlUri.startsWith(ApplicationConstants.VAADIN_PROTOCOL_PREFIX)) {
            final String vaadinUri = configuration.getVaadinDirUrl();
            String relativeUrl = uidlUri
                    .substring(ApplicationConstants.VAADIN_PROTOCOL_PREFIX
                            .length());
            uidlUri = vaadinUri + relativeUrl;
        }

        return uidlUri;
    }

    /**
     * Gets the URI for the current theme. Can be used to reference theme
     * resources.
     * 
     * @return URI to the current theme
     */
    public String getThemeUri() {
        return configuration.getThemeUri();
    }

    /**
     * Listens for Notification hide event, and redirects. Used for system
     * messages, such as session expired.
     * 
     */
    private class NotificationRedirect implements VNotification.EventListener {
        String url;

        NotificationRedirect(String url) {
            this.url = url;
        }

        @Override
        public void notificationHidden(HideEvent event) {
            redirect(url);
        }

    }

    /* Extended title handling */

    private final VTooltip tooltip;

    private ConnectorMap connectorMap = GWT.create(ConnectorMap.class);

    protected String getUidlSecurityKey() {
        return getCsrfToken();
    }

    /**
     * Gets the token (aka double submit cookie) that the server uses to protect
     * against Cross Site Request Forgery attacks.
     * 
     * @return the CSRF token string
     */
    public String getCsrfToken() {
        return csrfToken;
    }

    /**
     * Use to notify that the given component's caption has changed; layouts may
     * have to be recalculated.
     * 
     * @param component
     *            the Paintable whose caption has changed
     * @deprecated As of 7.0.2, has not had any effect for a long time
     */
    @Deprecated
    public void captionSizeUpdated(Widget widget) {
        // This doesn't do anything, it's just kept here for compatibility
    }

    /**
     * Gets the main view
     * 
     * @return the main view
     */
    public UIConnector getUIConnector() {
        return uIConnector;
    }

    /**
     * Gets the {@link ApplicationConfiguration} for the current application.
     * 
     * @see ApplicationConfiguration
     * @return the configuration for this application
     */
    public ApplicationConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Checks if there is a registered server side listener for the event. The
     * list of events which has server side listeners is updated automatically
     * before the component is updated so the value is correct if called from
     * updatedFromUIDL.
     * 
     * @param paintable
     *            The connector to register event listeners for
     * @param eventIdentifier
     *            The identifier for the event
     * @return true if at least one listener has been registered on server side
     *         for the event identified by eventIdentifier.
     * @deprecated As of 7.0. Use
     *             {@link AbstractComponentState#hasEventListener(String)}
     *             instead
     */
    @Deprecated
    public boolean hasEventListeners(ComponentConnector paintable,
            String eventIdentifier) {
        return paintable.hasEventListener(eventIdentifier);
    }

    /**
     * Adds the get parameters to the uri and returns the new uri that contains
     * the parameters.
     * 
     * @param uri
     *            The uri to which the parameters should be added.
     * @param extraParams
     *            One or more parameters in the format "a=b" or "c=d&e=f". An
     *            empty string is allowed but will not modify the url.
     * @return The modified URI with the get parameters in extraParams added.
     */
    public static String addGetParameters(String uri, String extraParams) {
        if (extraParams == null || extraParams.length() == 0) {
            return uri;
        }
        // RFC 3986: The query component is indicated by the first question
        // mark ("?") character and terminated by a number sign ("#") character
        // or by the end of the URI.
        String fragment = null;
        int hashPosition = uri.indexOf('#');
        if (hashPosition != -1) {
            // Fragment including "#"
            fragment = uri.substring(hashPosition);
            // The full uri before the fragment
            uri = uri.substring(0, hashPosition);
        }

        if (uri.contains("?")) {
            uri += "&";
        } else {
            uri += "?";
        }
        uri += extraParams;

        if (fragment != null) {
            uri += fragment;
        }

        return uri;
    }

    ConnectorMap getConnectorMap() {
        return connectorMap;
    }

    /**
     * @deprecated As of 7.0. No longer serves any purpose.
     */
    @Deprecated
    public void unregisterPaintable(ServerConnector p) {
        VConsole.log("unregisterPaintable (unnecessarily) called for "
                + Util.getConnectorString(p));
    }

    /**
     * Get VTooltip instance related to application connection
     * 
     * @return VTooltip instance
     */
    public VTooltip getVTooltip() {
        return tooltip;
    }

    /**
     * Method provided for backwards compatibility. Duties previously done by
     * this method is now handled by the state change event handler in
     * AbstractComponentConnector. The only function this method has is to
     * return true if the UIDL is a "cached" update.
     * 
     * @param component
     * @param uidl
     * @param manageCaption
     * @deprecated As of 7.0, no longer serves any purpose
     * @return
     */
    @Deprecated
    public boolean updateComponent(Widget component, UIDL uidl,
            boolean manageCaption) {
        ComponentConnector connector = getConnectorMap()
                .getConnector(component);
        if (!AbstractComponentConnector.isRealUpdate(uidl)) {
            return true;
        }

        if (!manageCaption) {
            VConsole.error(Util.getConnectorString(connector)
                    + " called updateComponent with manageCaption=false. The parameter was ignored - override delegateCaption() to return false instead. It is however not recommended to use caption this way at all.");
        }
        return false;
    }

    /**
     * @deprecated As of 7.0. Use
     *             {@link AbstractComponentConnector#hasEventListener(String)}
     *             instead
     */
    @Deprecated
    public boolean hasEventListeners(Widget widget, String eventIdentifier) {
        ComponentConnector connector = getConnectorMap().getConnector(widget);
        if (connector == null) {
            /*
             * No connector will exist in cases where Vaadin widgets have been
             * re-used without implementing server<->client communication.
             */
            return false;
        }

        return hasEventListeners(getConnectorMap().getConnector(widget),
                eventIdentifier);
    }

    LayoutManager getLayoutManager() {
        return layoutManager;
    }

    /**
     * Schedules a heartbeat request to occur after the configured heartbeat
     * interval elapses if the interval is a positive number. Otherwise, does
     * nothing.
     * 
     * @see #sendHeartbeat()
     * @see ApplicationConfiguration#getHeartbeatInterval()
     */
    protected void scheduleHeartbeat() {
        final int interval = getConfiguration().getHeartbeatInterval();
        if (interval > 0) {
            VConsole.log("Scheduling heartbeat in " + interval + " seconds");
            new Timer() {
                @Override
                public void run() {
                    sendHeartbeat();
                }
            }.schedule(interval * 1000);
        }
    }

    /**
     * Sends a heartbeat request to the server.
     * <p>
     * Heartbeat requests are used to inform the server that the client-side is
     * still alive. If the client page is closed or the connection lost, the
     * server will eventually close the inactive UI.
     * <p>
     * <b>TODO</b>: Improved error handling, like in doUidlRequest().
     * 
     * @see #scheduleHeartbeat()
     */
    protected void sendHeartbeat() {
        final String uri = addGetParameters(
                translateVaadinUri(ApplicationConstants.APP_PROTOCOL_PREFIX
                        + ApplicationConstants.HEARTBEAT_PATH + '/'),
                UIConstants.UI_ID_PARAMETER + "="
                        + getConfiguration().getUIId());

        final RequestBuilder rb = new RequestBuilder(RequestBuilder.POST, uri);

        final RequestCallback callback = new RequestCallback() {

            @Override
            public void onResponseReceived(Request request, Response response) {
                int status = response.getStatusCode();
                if (status == Response.SC_OK) {
                    // TODO Permit retry in some error situations
                    VConsole.log("Heartbeat response OK");
                    scheduleHeartbeat();
                } else if (status == Response.SC_GONE) {
                    showSessionExpiredError(null);
                } else {
                    VConsole.error("Failed sending heartbeat to server. Error code: "
                            + status);
                }
            }

            @Override
            public void onError(Request request, Throwable exception) {
                VConsole.error("Exception sending heartbeat: " + exception);
            }
        };

        rb.setCallback(callback);

        try {
            VConsole.log("Sending heartbeat request...");
            rb.send();
        } catch (RequestException re) {
            callback.onError(null, re);
        }
    }

    /**
     * Timer used to make sure that no misbehaving components can delay response
     * handling forever.
     */
    Timer forceHandleMessage = new Timer() {
        @Override
        public void run() {
            VConsole.log("WARNING: reponse handling was never resumed, forcibly removing locks...");
            responseHandlingLocks.clear();
            handlePendingMessages();
        }
    };

    /**
     * This method can be used to postpone rendering of a response for a short
     * period of time (e.g. to avoid the rendering process during animation).
     * 
     * @param lock
     */
    public void suspendReponseHandling(Object lock) {
        responseHandlingLocks.add(lock);
    }

    /**
     * Resumes the rendering process once all locks have been removed.
     * 
     * @param lock
     */
    public void resumeResponseHandling(Object lock) {
        responseHandlingLocks.remove(lock);
        if (responseHandlingLocks.isEmpty()) {
            // Cancel timer that breaks the lock
            forceHandleMessage.cancel();

            if (!pendingUIDLMessages.isEmpty()) {
                VConsole.log("No more response handling locks, handling pending requests.");
                handlePendingMessages();
            }
        }
    }

    /**
     * Handles all pending UIDL messages queued while response handling was
     * suspended.
     */
    private void handlePendingMessages() {
        if (!pendingUIDLMessages.isEmpty()) {
            /*
             * Clear the list before processing enqueued messages to support
             * reentrancy
             */
            List<PendingUIDLMessage> pendingMessages = pendingUIDLMessages;
            pendingUIDLMessages = new ArrayList<PendingUIDLMessage>();

            for (PendingUIDLMessage pending : pendingMessages) {
                handleReceivedJSONMessage(pending.getStart(),
                        pending.getJsonText(), pending.getJson());
            }
        }
    }

    private boolean handleErrorInDelegate(String details, int statusCode) {
        if (communicationErrorDelegate == null) {
            return false;
        }
        return communicationErrorDelegate.onError(details, statusCode);
    }

    /**
     * Sets the delegate that is called whenever a communication error occurrs.
     * 
     * @param delegate
     *            the delegate.
     */
    public void setCommunicationErrorDelegate(CommunicationErrorHandler delegate) {
        communicationErrorDelegate = delegate;
    }

    public void setApplicationRunning(boolean running) {
        applicationRunning = running;
    }

    public boolean isApplicationRunning() {
        return applicationRunning;
    }

    public <H extends EventHandler> HandlerRegistration addHandler(
            GwtEvent.Type<H> type, H handler) {
        return eventBus.addHandler(type, handler);
    }

    /**
     * Calls {@link ComponentConnector#flush()} on the active connector. Does
     * nothing if there is no active (focused) connector.
     */
    public void flushActiveConnector() {
        ComponentConnector activeConnector = getActiveConnector();
        if (activeConnector == null) {
            return;
        }
        activeConnector.flush();
    }

    /**
     * Gets the active connector for focused element in browser.
     * 
     * @return Connector for focused element or null.
     */
    private ComponentConnector getActiveConnector() {
        Element focusedElement = Util.getFocusedElement();
        if (focusedElement == null) {
            return null;
        }
        return Util.getConnectorForElement(this, getUIConnector().getWidget(),
                focusedElement);
    }

    /**
     * Sets the status for the push connection.
     * 
     * @param enabled
     *            <code>true</code> to enable the push connection;
     *            <code>false</code> to disable the push connection.
     */
    public void setPushEnabled(boolean enabled) {
        final PushConfigurationState pushState = uIConnector.getState().pushConfiguration;

        if (enabled && push == null) {
            push = GWT.create(PushConnection.class);
            push.init(this, pushState, new CommunicationErrorHandler() {
                @Override
                public boolean onError(String details, int statusCode) {
                    showCommunicationError(details, statusCode);
                    return true;
                }
            });
        } else if (!enabled && push != null && push.isActive()) {
            push.disconnect(new Command() {
                @Override
                public void execute() {
                    push = null;
                    /*
                     * If push has been enabled again while we were waiting for
                     * the old connection to disconnect, now is the right time
                     * to open a new connection
                     */
                    if (pushState.mode.isEnabled()) {
                        setPushEnabled(true);
                    }

                    /*
                     * Send anything that was enqueued while we waited for the
                     * connection to close
                     */
                    if (pendingInvocations.size() > 0) {
                        sendPendingVariableChanges();
                    }
                }
            });
        }
    }

    public void handlePushMessage(String message) {
        handleJSONText(message, 200);
    }

    /**
     * Returns a human readable string representation of the method used to
     * communicate with the server.
     * 
     * @since 7.1
     * @return A string representation of the current transport type
     */
    public String getCommunicationMethodName() {
        if (push != null) {
            return "Push (" + push.getTransportType() + ")";
        } else {
            return "XHR";
        }
    }

}
