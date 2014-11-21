package im.delight.android.ddp;

/**
 * Copyright 2014 www.delight.im <info@delight.im>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.Iterator;
import org.codehaus.jackson.map.ObjectMapper;
import java.util.UUID;
import java.util.Arrays;
import java.io.IOException;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.JsonNode;
import java.util.HashMap;
import java.util.Map;
import de.tavendo.autobahn.WebSocketException;
import de.tavendo.autobahn.WebSocketHandler;
import de.tavendo.autobahn.WebSocketConnection;

/** Client that connects to Meteor servers implementing the DDP protocol */
public class Meteor {

	/** Internal version name for this library that follows the Semantic Versioning scheme */
	public static final String LIBRARY_VERSION_NAME = "0.0.1";
	/** Supported versions of the DDP protocol in order of preference */
	private static final String[] SUPPORTED_DDP_VERSIONS = { "1", "pre2", "pre1" };
	/** Whether logging should be enabled or not (behaviour can be adjusted in log() method */
	private static final boolean LOGGING_ENABLED = true;
	/** The maximum number of attempts to re-connect to the server over WebSocket */
	private static final int RECONNECT_ATTEMPTS_MAX = 5;
	/** Instance of Jackson library's ObjectMapper that converts between JSON and Java objects (POJOs) */
	private static final ObjectMapper mObjectMapper = new ObjectMapper();
	/** The WebSocket connection that will be used for the data transfer */
	private final WebSocketConnection mConnection;
	/** The callback that handles messages and events received from the WebSocket connection */
	private final WebSocketHandler mWebSocketHandler;
	/** Map that tracks all pending Listener instances */
	private final Map<String, Listener> mListeners;
	/** Messages that couldn't be dispatched yet and thus had to be queued */
	private final Queue<String> mQueuedMessages;
	private String mServerUri;
	private String mDdpVersion;
	/** The number of unsuccessful attempts to re-connect in sequence */
	private int mReconnectAttempts;
	/** The callback that will handle events and receive messages from this client */
	private MeteorCallback mCallback;
	private String mSessionID;
	private boolean mConnected;

	/**
	 * Returns a new instance for a client connecting to a server via DDP over websocket
	 *
	 * The server URI should usually be in the form of `ws://example.meteor.com/websocket`
	 *
	 * @param serverUri the server URI to connect to
	 */
	public Meteor(final String serverUri) {
		this(serverUri, SUPPORTED_DDP_VERSIONS[0]);
	}

	/**
	 * Returns a new instance for a client connecting to a server via DDP over websocket
	 *
	 * The server URI should usually be in the form of `ws://example.meteor.com/websocket`
	 *
	 * @param serverUri the server URI to connect to
	 * @param protocolVersion the desired DDP protocol version
	 */
	public Meteor(final String serverUri, final String protocolVersion) {
		if (!isVersionSupported(protocolVersion)) {
			throw new RuntimeException("DDP protocol version not supported: "+protocolVersion);
		}

		// create a new WebSocket connection for the data transfer
		mConnection = new WebSocketConnection();

		// create a new handler that processes the messages and events received from the WebSocket connection
		mWebSocketHandler = new WebSocketHandler() {

			@Override
			public void onOpen() {
				log("onOpen()");
				mConnected = true;
				mReconnectAttempts = 0;
				connect(mSessionID);
			}

			@Override
			public void onClose(int code, String reason) {
				log("onClose()");
				final boolean lostConnection = mConnected;
				mConnected = false;
				if (lostConnection) {
					mReconnectAttempts++;
					if (mReconnectAttempts <= RECONNECT_ATTEMPTS_MAX) {
						// try to re-connect automatically
						openConnection(false);
					}
					else {
						disconnect();
					}
				}

				if (mCallback != null) {
					mCallback.onDisconnect(code, reason);
				}
			}

			@Override
			public void onTextMessage(String payload) {
				handleMessage(payload);
			}

		};

		// create a map that holds the pending Listener instances
		mListeners = new HashMap<String, Listener>();

		// create a queue that holds undispatched messages waiting to be sent
		mQueuedMessages = new ConcurrentLinkedQueue<String>();

		// save the server URI
		mServerUri = serverUri;
		// try with the preferred DDP protocol version first
		mDdpVersion = protocolVersion;
		// count the number of failed attempts to re-connect
		mReconnectAttempts = 0;

		openConnection(false);
	}

	/**
	 * Returns whether this client is connected or not
	 *
	 * @return whether this client is connected
	 */
	public boolean isConnected() {
		return mConnected;
	}

	/** Manually attempt to re-connect if necessary */
	public void reconnect() {
		openConnection(true);
	}

	/**
	 * Opens a connection to the server over websocket
	 *
	 * @param isReconnect whether this is a re-connect attempt or not
	 */
	private void openConnection(final boolean isReconnect) {
		if (isReconnect) {
			if (mConnection.isConnected()) {
				connect(mSessionID);
				return;
			}
		}

		try {
			mConnection.connect(mServerUri, mWebSocketHandler);
		}
		catch (WebSocketException e) {
			if (mCallback != null) {
				mCallback.onException(e);
			}
		}
	}

	/**
	 * Establish the connection to the server as requested by the DDP protocol (after the websocket has been opened)
	 *
	 * @param existingSessionID an existing session ID or `null`
	 */
	private void connect(final String existingSessionID) {
		final Map<String, Object> data = new HashMap<>();
		data.put(Protocol.Field.MESSAGE, Protocol.Message.CONNECT);
		data.put(Protocol.Field.VERSION, mDdpVersion);
		data.put(Protocol.Field.SUPPORT, SUPPORTED_DDP_VERSIONS);
		if (existingSessionID != null) {
			data.put(Protocol.Field.SESSION, existingSessionID);
		}
		send(data);
	}

	/** Disconnect the client from the server */
	public void disconnect() {
		mConnected = false;
		mListeners.clear();
		mSessionID = null;
		mCallback = null;
		try {
			mConnection.disconnect();
		}
		catch (Exception e) { }
	}

	/**
	 * Sends a Java object (POJO) over the websocket after serializing it with the Jackson library
	 *
	 * @param obj the Java object to send
	 */
	private void send(final Object obj) {
		// serialize the object to JSON
		final String jsonStr = toJson(obj);

		if (jsonStr == null) {
			throw new RuntimeException("Object would be serialized to `null`");
		}

		// send the JSON string
		send(jsonStr);
	}

	/**
	 * Sends a string over the websocket
	 *
	 * @param message the string to send
	 */
	private void send(final String message) {
		if (message == null) {
			throw new RuntimeException("You cannot send `null` messages");
		}

		if (mConnected) {
			log("SEND: "+message);
			mConnection.sendTextMessage(message);
		}
		else {
			log("QUEUE: "+message);
			mQueuedMessages.add(message);
		}
	}

	/**
	 * Sets the callback that will handle events and receive messages from this client
	 *
	 * @param callback the callback instance
	 */
	public void setCallback(MeteorCallback callback) {
		mCallback = callback;
	}

	/**
	 * Serializes the given Java object (POJO) with the Jackson library
	 *
	 * @param obj the object to serialize
	 * @return the serialized object in JSON format
	 */
	private String toJson(Object obj) {
		try {
			return mObjectMapper.writeValueAsString(obj);
		}
		catch (Exception e) {
			if (mCallback != null) {
				mCallback.onException(e);
			}
			return null;
		}
	}

	/**
	 * Called whenever a JSON payload has been received from the websocket
	 *
	 * @param payload the JSON payload to process
	 */
	private void handleMessage(final String payload) {
		log("RECEIVE: "+payload);

		JsonNode data;
		try {
			data = mObjectMapper.readTree(payload);
		}
		catch (JsonProcessingException e) {
			if (mCallback != null) {
				mCallback.onException(e);
			}
			return;
		}
		catch (IOException e) {
			if (mCallback != null) {
				mCallback.onException(e);
			}
			return;
		}

		if (data != null) {
			if (data.has(Protocol.Field.MESSAGE)) {
				final String message = data.get(Protocol.Field.MESSAGE).getTextValue();

				if (message.equals(Protocol.Message.CONNECTED)) {
					if (data.has(Protocol.Field.SESSION)) {
						mSessionID = data.get(Protocol.Field.SESSION).getTextValue();
					}

					if (mCallback != null) {
						mCallback.onConnect();
					}

					// try to dispatch queued messages now
					for (String queuedMessage : mQueuedMessages) {
						send(queuedMessage);
					}
				}
				else if (message.equals(Protocol.Message.FAILED)) {
					if (data.has(Protocol.Field.VERSION)) {
						final String desiredVersion = data.get(Protocol.Field.VERSION).getTextValue();

						if (isVersionSupported(desiredVersion)) {
							mDdpVersion = desiredVersion;

							openConnection(true);
						}
						else {
							throw new RuntimeException("Protocol version not supported: "+desiredVersion);
						}
					}
				}
				else if (message.equals(Protocol.Message.PING)) {
					final String id;
					if (data.has(Protocol.Field.ID)) {
						id = data.get(Protocol.Field.ID).getTextValue();
					}
					else {
						id = null;
					}

					sendPong(id);
				}
				else if (message.equals(Protocol.Message.ADDED) || message.equals(Protocol.Message.ADDED_BEFORE)) {
					final String documentID;
					if (data.has(Protocol.Field.ID)) {
						documentID = data.get(Protocol.Field.ID).getTextValue();
					}
					else {
						documentID = null;
					}

					final String collectionName;
					if (data.has(Protocol.Field.COLLECTION)) {
						collectionName = data.get(Protocol.Field.COLLECTION).getTextValue();
					}
					else {
						collectionName = null;
					}

					final String newValuesJson;
					if (data.has(Protocol.Field.FIELDS)) {
						newValuesJson = data.get(Protocol.Field.FIELDS).toString();
					}
					else {
						newValuesJson = null;
					}

					if (mCallback != null) {
						mCallback.onDataAdded(collectionName, documentID, newValuesJson);
					}
				}
				else if (message.equals(Protocol.Message.CHANGED)) {
					final String documentID;
					if (data.has(Protocol.Field.ID)) {
						documentID = data.get(Protocol.Field.ID).getTextValue();
					}
					else {
						documentID = null;
					}

					final String collectionName;
					if (data.has(Protocol.Field.COLLECTION)) {
						collectionName = data.get(Protocol.Field.COLLECTION).getTextValue();
					}
					else {
						collectionName = null;
					}

					final String updatedValuesJson;
					if (data.has(Protocol.Field.FIELDS)) {
						updatedValuesJson = data.get(Protocol.Field.FIELDS).toString();
					}
					else {
						updatedValuesJson = null;
					}

					final String removedValuesJson;
					if (data.has(Protocol.Field.CLEARED)) {
						removedValuesJson = data.get(Protocol.Field.CLEARED).toString();
					}
					else {
						removedValuesJson = null;
					}

					if (mCallback != null) {
						mCallback.onDataChanged(collectionName, documentID, updatedValuesJson, removedValuesJson);
					}
				}
				else if (message.equals(Protocol.Message.REMOVED)) {
					final String documentID;
					if (data.has(Protocol.Field.ID)) {
						documentID = data.get(Protocol.Field.ID).getTextValue();
					}
					else {
						documentID = null;
					}

					final String collectionName;
					if (data.has(Protocol.Field.COLLECTION)) {
						collectionName = data.get(Protocol.Field.COLLECTION).getTextValue();
					}
					else {
						collectionName = null;
					}

					if (mCallback != null) {
						mCallback.onDataRemoved(collectionName, documentID);
					}
				}
				else if (message.equals(Protocol.Message.RESULT)) {
					final String id;
					if (data.has(Protocol.Field.ID)) {
						id = data.get(Protocol.Field.ID).getTextValue();
					}
					else {
						id = null;
					}

					final Listener listener = mListeners.get(id);

					if (listener instanceof ResultListener) {
						mListeners.remove(id);

						final String result;
						if (data.has(Protocol.Field.RESULT)) {
							result = data.get(Protocol.Field.RESULT).toString();
						}
						else {
							result = null;
						}

						if (data.has(Protocol.Field.ERROR)) {
							final Protocol.Error error = Protocol.Error.fromJson(data.get(Protocol.Field.ERROR));
							((ResultListener) listener).onError(error.getError(), error.getReason(), error.getDetails());
						}
						else {
							((ResultListener) listener).onSuccess(result);
						}
					}
				}
				else if (message.equals(Protocol.Message.READY)) {
					if (data.has(Protocol.Field.SUBS)) {
						final Iterator<JsonNode> elements = data.get(Protocol.Field.SUBS).getElements();
						String subscriptionId;
						while (elements.hasNext()) {
							subscriptionId = elements.next().getTextValue();

							final Listener listener = mListeners.get(subscriptionId);

							if (listener instanceof SubscribeListener) {
								mListeners.remove(subscriptionId);

								((SubscribeListener) listener).onSuccess();
							}
						}
					}
				}
				else if (message.equals(Protocol.Message.NOSUB)) {
					final String subscriptionId;
					if (data.has(Protocol.Field.ID)) {
						subscriptionId = data.get(Protocol.Field.ID).getTextValue();
					}
					else {
						subscriptionId = null;
					}

					final Listener listener = mListeners.get(subscriptionId);

					if (listener instanceof SubscribeListener) {
						mListeners.remove(subscriptionId);

						if (data.has(Protocol.Field.ERROR)) {
							final Protocol.Error error = Protocol.Error.fromJson(data.get(Protocol.Field.ERROR));
							((SubscribeListener) listener).onError(error.getError(), error.getReason(), error.getDetails());
						}
						else {
							((SubscribeListener) listener).onError(null, null, null);
						}
					}
					else if (listener instanceof UnsubscribeListener) {
						mListeners.remove(subscriptionId);

						((UnsubscribeListener) listener).onSuccess();
					}
				}
			}
		}
	}

	/**
	 * Returns whether the specified version of the DDP protocol is supported or not
	 *
	 * @param protocolVersion the DDP protocol version
	 * @return whether the version is supported or not
	 */
	public static boolean isVersionSupported(final String protocolVersion) {
		return Arrays.asList(SUPPORTED_DDP_VERSIONS).contains(protocolVersion);
	}

	/**
	 * Sends a `pong` over the websocket as a reply to an incoming `ping`
	 *
	 * @param id the ID extracted from the `ping` or `null`
	 */
	private void sendPong(final String id) {
		final Map<String, Object> data = new HashMap<>();
		data.put(Protocol.Field.MESSAGE, Protocol.Message.PONG);
		if (id != null) {
			data.put(Protocol.Field.ID, id);
		}
		send(data);
	}

	/**
	 * Logs a message if logging has been enabled
	 *
	 * @param message the message to log
	 */
	public static void log(final String message) {
		if (LOGGING_ENABLED) {
			System.out.println(message);
		}
	}

	/**
	 * Creates and returns a new unique ID
	 *
	 * @return the new unique ID
	 */
	public static String uniqueID() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param data the data to insert
	 */
	public void insert(final String collectionName, final Map<String, Object> data) {
		insert(collectionName, data, null);
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param data the data to insert
	 * @param listener the listener to call on success/error
	 */
	public void insert(final String collectionName, final Map<String, Object> data, final ResultListener listener) {
		call("/"+collectionName+"/insert", new Object[] { data }, listener);
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param query the query to select the document to update with
	 * @param data the list of keys and values that should be set
	 */
	public void update(final String collectionName, final Map<String, Object> query, final Map<String, Object> data) {
		update(collectionName, query, data, emptyMap());
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param query the query to select the document to update with
	 * @param data the list of keys and values that should be set
	 * @param options the list of option parameters
	 */
	public void update(final String collectionName, final Map<String, Object> query, final Map<String, Object> data, final Map<String, Object> options) {
		update(collectionName, query, data, options, null);
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param query the query to select the document to update with
	 * @param data the list of keys and values that should be set
	 * @param options the list of option parameters
	 * @param listener the listener to call on success/error
	 */
	public void update(final String collectionName, final Map<String, Object> query, final Map<String, Object> data, final Map<String, Object> options, final ResultListener listener) {
		call("/"+collectionName+"/update", new Object[] { query, data, options }, listener);
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param documentID the ID of the document to remove
	 */
	public void remove(final String collectionName, final String documentID) {
		remove(collectionName, documentID, null);
	}

	/**
	 * Insert given data into the specified collection
	 *
	 * @param collectionName the collection to insert the data into
	 * @param documentId the ID of the document to remove
	 * @param listener the listener to call on success/error
	 */
	public void remove(final String collectionName, final String documentId, final ResultListener listener) {
		Map<String, Object> query = new HashMap<String, Object>();
		query.put(MongoDb.Field.ID, documentId);
		call("/"+collectionName+"/remove", new Object[] { query }, listener);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 */
	public void call(final String methodName) {
		call(methodName, null, null);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 * @param params the objects that should be passed to the method as parameters
	 */
	public void call(final String methodName, final Object[] params) {
		call(methodName, params, null);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 * @param listener the listener to trigger when the result has been received or `null`
	 */
	public void call(final String methodName, final ResultListener listener) {
		call(methodName, null, listener);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 * @param params the objects that should be passed to the method as parameters
	 * @param listener the listener to trigger when the result has been received or `null`
	 */
	public void call(final String methodName, final Object[] params, final ResultListener listener) {
		callWithSeed(methodName, null, params, listener);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 * @param randomSeed an arbitrary seed for pseudo-random generators or `null`
	 */
	public void callWithSeed(final String methodName, final String randomSeed) {
		callWithSeed(methodName, randomSeed, null, null);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 * @param randomSeed an arbitrary seed for pseudo-random generators or `null`
	 * @param params the objects that should be passed to the method as parameters
	 */
	public void callWithSeed(final String methodName, final String randomSeed, final Object[] params) {
		callWithSeed(methodName, randomSeed, params, null);
	}

	/**
	 * Executes a remote procedure call (any Java objects (POJOs) will be serialized to JSON by the Jackson library)
	 *
	 * @param methodName the name of the method to call, e.g. `/someCollection.insert`
	 * @param randomSeed an arbitrary seed for pseudo-random generators or `null`
	 * @param params the objects that should be passed to the method as parameters
	 * @param listener the listener to trigger when the result has been received or `null`
	 */
	public void callWithSeed(final String methodName, final String randomSeed, final Object[] params, final ResultListener listener) {
		// create a new unique ID for this request
		final String callId = uniqueID();

		// save a reference to the listener to be executed later
		if (listener != null) {
			mListeners.put(callId, listener);
		}

		// send the request
		final Map<String, Object> data = new HashMap<>();
		data.put(Protocol.Field.MESSAGE, Protocol.Message.METHOD);
		data.put(Protocol.Field.METHOD, methodName);
		data.put(Protocol.Field.ID, callId);
		if (params != null) {
			data.put(Protocol.Field.PARAMS, params);
		}
		if (randomSeed != null) {
			data.put(Protocol.Field.RANDOM_SEED, randomSeed);
		}
		send(data);
	}

	/**
	 * Subscribes to a specific subscription from the server
	 *
	 * @param subscriptionName the name of the subscription
	 * @return the generated subscription ID (must be used when unsubscribing)
	 */
	public String subscribe(final String subscriptionName) {
		return subscribe(subscriptionName, null);
	}

	/**
	 * Subscribes to a specific subscription from the server
	 *
	 * @param subscriptionName the name of the subscription
	 * @param params the subscription parameters
	 * @return the generated subscription ID (must be used when unsubscribing)
	 */
	public String subscribe(final String subscriptionName, final Object[] params) {
		return subscribe(subscriptionName, params, null);
	}

	/**
	 * Subscribes to a specific subscription from the server
	 *
	 * @param subscriptionName the name of the subscription
	 * @param params the subscription parameters
	 * @param listener the listener to call on success/error
	 * @return the generated subscription ID (must be used when unsubscribing)
	 */
	public String subscribe(final String subscriptionName, final Object[] params, final SubscribeListener listener) {
		// create a new unique ID for this request
		final String subscriptionId = uniqueID();

		// save a reference to the listener to be executed later
		if (listener != null) {
			mListeners.put(subscriptionId, listener);
		}

		// send the request
		final Map<String, Object> data = new HashMap<>();
		data.put(Protocol.Field.MESSAGE, Protocol.Message.SUBSCRIBE);
		data.put(Protocol.Field.NAME, subscriptionName);
		data.put(Protocol.Field.ID, subscriptionId);
		if (params != null) {
			data.put(Protocol.Field.PARAMS, params);
		}
		send(data);
		
		// return the generated subscription ID
		return subscriptionId;
	}

	/**
	 * Unsubscribes from the subscription with the specified name
	 *
	 * @param subscriptionId the ID of the subscription
	 */
	public void unsubscribe(final String subscriptionId) {
		unsubscribe(subscriptionId, null);
	}

	/**
	 * Unsubscribes from the subscription with the specified name
	 *
	 * @param subscriptionId the ID of the subscription
	 * @param listener the listener to call on success/error
	 */
	public void unsubscribe(final String subscriptionId, final UnsubscribeListener listener) {
		// save a reference to the listener to be executed later
		if (listener != null) {
			mListeners.put(subscriptionId, listener);
		}

		// send the request
		final Map<String, Object> data = new HashMap<>();
		data.put(Protocol.Field.MESSAGE, Protocol.Message.UNSUBSCRIBE);
		data.put(Protocol.Field.ID, subscriptionId);
		send(data);
	}

	/**
	 * Creates an empty map for use as default parameter
	 *
	 * @return an empty map
	 */
	private static Map<String, Object> emptyMap() {
		return new HashMap<String, Object>();
	}

}
