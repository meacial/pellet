package com.clarkparsia.pellet.server.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;

import com.clarkparsia.pellet.server.exceptions.ServerException;
import com.clarkparsia.pellet.server.model.OntologyState;
import com.clarkparsia.pellet.server.model.ServerState;
import com.clarkparsia.pellet.service.ServiceDecoder;
import com.clarkparsia.pellet.service.ServiceEncoder;
import com.clarkparsia.pellet.service.reasoner.SchemaReasoner;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;
import org.semanticweb.owlapi.model.IRI;

/**
 * Abstract implementation of HttpHandler with tools to reuse across all
 * HttpHandlers implemented for the Pellet Server.
 *
 * @author Edgar Rodriguez-Diaz
 */
public abstract class AbstractReasonerHandler implements HttpHandler {

	private final ServerState serverState;

	private final Collection<ServiceEncoder> mEncoders;

	private final Collection<ServiceDecoder> mDecoders;

	public AbstractReasonerHandler(final ServerState theServerState,
	                               final Collection<ServiceEncoder> theEncoders,
	                               final Collection<ServiceDecoder> theDecoders) {
		serverState = theServerState;
		mEncoders = theEncoders;
		mDecoders = theDecoders;
	}

	protected ServerState getServerState() {
		return serverState;
	}

	protected Optional<ServiceEncoder> getEncoder(final String theMediaType) {
		Optional<ServiceEncoder> aFound = Optional.absent();

		for (ServiceEncoder encoder : mEncoders) {
			if (encoder.canEncode(theMediaType)) {
				aFound = Optional.of(encoder);
			}
		}

		return aFound;
	}

	protected Optional<ServiceDecoder> getDecoder(final String theMediaType) {
		Optional<ServiceDecoder> aFound = Optional.absent();

		for (ServiceDecoder decoder : mDecoders) {
			if (decoder.canDecode(theMediaType)) {
				aFound = Optional.of(decoder);
			}
		}

		return aFound;
	}

	protected SchemaReasoner getReasoner(final IRI theOntology, final UUID theClientId) throws ServerException {
		return getOntologyState(theOntology).getClient(theClientId.toString())
		                                    .getReasoner();
	}

	protected OntologyState getOntologyState(final IRI theOntology) throws ServerException {
		Optional<OntologyState> aOntoState = getServerState().getOntology(theOntology);
		if (!aOntoState.isPresent()) {
			throw new ServerException(StatusCodes.NOT_FOUND, "Ontology not found: " + theOntology);
		}

		return aOntoState.get();
	}

	private String getHeaderValue(final HttpServerExchange theExchange,
	                              final HttpString theAttr,
	                              final String theDefault) {
		HeaderValues aVals = theExchange.getRequestHeaders().get(theAttr);

		return !aVals.isEmpty() ? aVals.getFirst()
		                        : theDefault;
	}

	/**
	 * TODO: Extend to handle multiple Accepts (the encoding/decoding too)
	 */
	protected String getAccept(final HttpServerExchange theExchange) {
		return getHeaderValue(theExchange,
		                      Headers.ACCEPT,
		                      mEncoders.iterator().next().getMediaType());
	}

	protected String getContentType(final HttpServerExchange theExchange) {
		return getHeaderValue(theExchange,
		                      Headers.CONTENT_TYPE,
		                      mDecoders.iterator().next().getMediaType());
	}

	protected void throwBadRequest(final String theMsg) throws ServerException {
		throw new ServerException(StatusCodes.BAD_REQUEST, theMsg);
	}

	protected IRI getOntology(final HttpServerExchange theExchange) throws ServerException {
		try {
			return IRI.create(URLDecoder.decode(theExchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY)
			                                               .getParameters().get("ontology"),
			                                    StandardCharsets.UTF_8.name()));
		}
		catch (Exception theE) {
			throw new ServerException(StatusCodes.BAD_REQUEST, "Error parsing Ontology IRI", theE);
		}
	}

	protected UUID getClientID(final HttpServerExchange theExchange) throws ServerException {
		try {
			return UUID.fromString(getQueryParameter(theExchange, "client"));
		}
		catch (IllegalArgumentException theE) {
			throw new ServerException(StatusCodes.BAD_REQUEST, "Error parsing Client ID - must be a UUID", theE);
		}
	}

	protected byte[] readInput(final InputStream theInStream,
	                           final boolean theFailOnEmpty /* Signal to fail on empty input */) throws ServerException {
		byte[] inBytes = {};
		try {
			try {
				inBytes = ByteStreams.toByteArray(theInStream);
			}
			finally {
				theInStream.close();
			}
		}
		catch (IOException theE) {
			throw new ServerException(500, "There was an IO error while reading input stream", theE);
		}

		if (theFailOnEmpty && inBytes.length == 0) {
			throw new ServerException(StatusCodes.NOT_ACCEPTABLE, "Payload is empty");
		}

		return inBytes;
	}

	protected String getQueryParameter(final HttpServerExchange theExchange,
	                                   final String theParamName) throws ServerException {
		final Map<String, Deque<String>> queryParams = theExchange.getQueryParameters();

		if (!queryParams.containsKey(theParamName) || queryParams.get(theParamName).isEmpty()) {
			throwBadRequest("Missing required query parameter: "+ theParamName);
		}

		final String paramVal = queryParams.get(theParamName).getFirst();
		if (Strings.isNullOrEmpty(paramVal)) {
			throwBadRequest("Query parameter ["+ theParamName +"] value is empty");
		}

		return paramVal;
	}
}
