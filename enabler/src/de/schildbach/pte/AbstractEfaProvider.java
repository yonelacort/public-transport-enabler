/*
 * Copyright 2010-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.pte;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.google.common.base.Charsets;

import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Fare;
import de.schildbach.pte.dto.Fare.Type;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.LineDestination;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.exception.InvalidDataException;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.ParserUtils;
import de.schildbach.pte.util.XmlPullUtil;

/**
 * @author Andreas Schildbach
 */
public abstract class AbstractEfaProvider extends AbstractNetworkProvider
{
	protected static final String DEFAULT_DEPARTURE_MONITOR_ENDPOINT = "XSLT_DM_REQUEST";
	protected static final String DEFAULT_TRIP_ENDPOINT = "XSLT_TRIP_REQUEST2";
	protected static final String DEFAULT_STOPFINDER_ENDPOINT = "XML_STOPFINDER_REQUEST";
	protected static final String DEFAULT_COORD_ENDPOINT = "XML_COORD_REQUEST";

	protected static final String SERVER_PRODUCT = "efa";

	private final String departureMonitorEndpoint;
	private final String tripEndpoint;
	private final String stopFinderEndpoint;
	private final String coordEndpoint;

	private String language = "de";
	private String additionalQueryParameter = null;
	private boolean useRealtime = true;
	private boolean needsSpEncId = false;
	private boolean includeRegionId = true;
	private boolean useProxFootSearch = true;
	private Charset requestUrlEncoding = Charsets.ISO_8859_1;
	private String httpReferer = null;
	private String httpRefererTrip = null;
	private boolean httpPost = false;
	private boolean useRouteIndexAsTripId = true;
	private boolean useLineRestriction = true;
	private boolean useStringCoordListOutputFormat = true;
	private float fareCorrectionFactor = 1f;

	private final XmlPullParserFactory parserFactory;

	private static class Context implements QueryTripsContext
	{
		private final String context;

		private Context(final String context)
		{
			this.context = context;
		}

		public boolean canQueryLater()
		{
			return context != null;
		}

		public boolean canQueryEarlier()
		{
			return false; // TODO enable earlier querying
		}

		@Override
		public String toString()
		{
			return getClass().getName() + "[" + context + "]";
		}
	}

	public AbstractEfaProvider(final String apiBase)
	{
		this(apiBase, null, null, null, null);
	}

	public AbstractEfaProvider(final String apiBase, final String departureMonitorEndpoint, final String tripEndpoint,
			final String stopFinderEndpoint, final String coordEndpoint)
	{
		this(apiBase + (departureMonitorEndpoint != null ? departureMonitorEndpoint : DEFAULT_DEPARTURE_MONITOR_ENDPOINT), //
				apiBase + (tripEndpoint != null ? tripEndpoint : DEFAULT_TRIP_ENDPOINT), //
				apiBase + (stopFinderEndpoint != null ? stopFinderEndpoint : DEFAULT_STOPFINDER_ENDPOINT), //
				apiBase + (coordEndpoint != null ? coordEndpoint : DEFAULT_COORD_ENDPOINT));
	}

	public AbstractEfaProvider(final String departureMonitorEndpoint, final String tripEndpoint, final String stopFinderEndpoint,
			final String coordEndpoint)
	{
		try
		{
			parserFactory = XmlPullParserFactory.newInstance(System.getProperty(XmlPullParserFactory.PROPERTY_NAME), null);
		}
		catch (final XmlPullParserException x)
		{
			throw new RuntimeException(x);
		}

		this.departureMonitorEndpoint = departureMonitorEndpoint;
		this.tripEndpoint = tripEndpoint;
		this.stopFinderEndpoint = stopFinderEndpoint;
		this.coordEndpoint = coordEndpoint;
	}

	protected void setLanguage(final String language)
	{
		this.language = language;
	}

	protected void setAdditionalQueryParameter(final String additionalQueryParameter)
	{
		this.additionalQueryParameter = additionalQueryParameter;
	}

	protected void setRequestUrlEncoding(final Charset requestUrlEncoding)
	{
		this.requestUrlEncoding = requestUrlEncoding;
	}

	protected void setHttpReferer(final String httpReferer)
	{
		this.httpReferer = httpReferer;
		this.httpRefererTrip = httpReferer;
	}

	public void setHttpRefererTrip(final String httpRefererTrip)
	{
		this.httpRefererTrip = httpRefererTrip;
	}

	protected void setHttpPost(final boolean httpPost)
	{
		this.httpPost = httpPost;
	}

	protected void setUseRealtime(final boolean useRealtime)
	{
		this.useRealtime = useRealtime;
	}

	protected void setIncludeRegionId(final boolean includeRegionId)
	{
		this.includeRegionId = includeRegionId;
	}

	protected void setUseProxFootSearch(final boolean useProxFootSearch)
	{
		this.useProxFootSearch = useProxFootSearch;
	}

	protected void setUseRouteIndexAsTripId(final boolean useRouteIndexAsTripId)
	{
		this.useRouteIndexAsTripId = useRouteIndexAsTripId;
	}

	protected void setUseLineRestriction(final boolean useLineRestriction)
	{
		this.useLineRestriction = useLineRestriction;
	}

	protected void setUseStringCoordListOutputFormat(final boolean useStringCoordListOutputFormat)
	{
		this.useStringCoordListOutputFormat = useStringCoordListOutputFormat;
	}

	protected void setNeedsSpEncId(final boolean needsSpEncId)
	{
		this.needsSpEncId = needsSpEncId;
	}

	protected void setFareCorrectionFactor(final float fareCorrectionFactor)
	{
		this.fareCorrectionFactor = fareCorrectionFactor;
	}

	@Override
	protected boolean hasCapability(final Capability capability)
	{
		return true;
	}

	private final void appendCommonRequestParams(final StringBuilder uri, final String outputFormat)
	{
		uri.append("?outputFormat=").append(outputFormat);
		uri.append("&language=").append(language);
		uri.append("&stateless=1");
		uri.append("&coordOutputFormat=WGS84");
		if (additionalQueryParameter != null)
			uri.append('&').append(additionalQueryParameter);
	}

	protected SuggestLocationsResult jsonStopfinderRequest(final Location constraint) throws IOException
	{
		final StringBuilder parameters = stopfinderRequestParameters(constraint, "JSON");

		final StringBuilder uri = new StringBuilder(stopFinderEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		final CharSequence page = ParserUtils.scrape(uri.toString(), httpPost ? parameters.substring(1) : null, Charsets.UTF_8);
		final ResultHeader header = new ResultHeader(SERVER_PRODUCT);

		try
		{
			final List<SuggestedLocation> locations = new ArrayList<SuggestedLocation>();

			final JSONObject head = new JSONObject(page.toString());
			final JSONObject stopFinder = head.optJSONObject("stopFinder");
			final JSONArray stops;
			if (stopFinder == null)
			{
				stops = head.getJSONArray("stopFinder");
			}
			else
			{
				final JSONObject points = stopFinder.optJSONObject("points");
				if (points != null)
				{
					final JSONObject stop = points.getJSONObject("point");
					final SuggestedLocation location = parseJsonStop(stop);
					locations.add(location);
					return new SuggestLocationsResult(header, locations);
				}

				stops = stopFinder.optJSONArray("points");
				if (stops == null)
					return new SuggestLocationsResult(header, locations);
			}

			final int nStops = stops.length();

			for (int i = 0; i < nStops; i++)
			{
				final JSONObject stop = stops.optJSONObject(i);
				final SuggestedLocation location = parseJsonStop(stop);
				locations.add(location);
			}

			return new SuggestLocationsResult(header, locations);
		}
		catch (final JSONException x)
		{
			throw new RuntimeException("cannot parse: '" + page + "' on " + uri, x);
		}
	}

	private SuggestedLocation parseJsonStop(final JSONObject stop) throws JSONException
	{
		String type = stop.getString("type");
		if ("any".equals(type))
			type = stop.getString("anyType");
		final String id = stop.getString("stateless");
		final String name = normalizeLocationName(stop.optString("name"));
		final String object = normalizeLocationName(stop.optString("object"));
		final String postcode = stop.optString("postcode");
		final int quality = stop.getInt("quality");
		final JSONObject ref = stop.getJSONObject("ref");
		String place = ref.getString("place");
		if (place != null && place.length() == 0)
			place = null;
		final Point coord = parseCoord(ref.optString("coords", null));

		final Location location;
		if ("stop".equals(type))
			location = new Location(LocationType.STATION, id, coord, place, object);
		else if ("poi".equals(type))
			location = new Location(LocationType.POI, id, coord, place, object);
		else if ("crossing".equals(type))
			location = new Location(LocationType.ADDRESS, id, coord, place, object);
		else if ("street".equals(type) || "address".equals(type) || "singlehouse".equals(type) || "buildingname".equals(type))
			location = new Location(LocationType.ADDRESS, id, coord, place, name);
		else if ("postcode".equals(type))
			location = new Location(LocationType.ADDRESS, id, coord, place, postcode);
		else
			throw new JSONException("unknown type: " + type);

		return new SuggestedLocation(location, quality);
	}

	private StringBuilder stopfinderRequestParameters(final Location constraint, final String outputFormat)
	{
		final StringBuilder parameters = new StringBuilder();
		appendCommonRequestParams(parameters, outputFormat);
		parameters.append("&locationServerActive=1");
		if (includeRegionId)
			parameters.append("&regionID_sf=1"); // prefer own region
		appendLocation(parameters, constraint, "sf");
		if (constraint.type == LocationType.ANY)
		{
			if (needsSpEncId)
				parameters.append("&SpEncId=0");
			// 1=place 2=stop 4=street 8=address 16=crossing 32=poi 64=postcode
			parameters.append("&anyObjFilter_sf=").append(2 + 4 + 8 + 16 + 32 + 64);
			parameters.append("&reducedAnyPostcodeObjFilter_sf=64&reducedAnyTooManyObjFilter_sf=2");
			parameters.append("&useHouseNumberList=true");
			parameters.append("&anyMaxSizeHitList=500");
		}

		return parameters;
	}

	protected SuggestLocationsResult xmlStopfinderRequest(final Location constraint) throws IOException
	{
		final StringBuilder parameters = stopfinderRequestParameters(constraint, "XML");

		final StringBuilder uri = new StringBuilder(stopFinderEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterItdRequest(pp);

			final List<SuggestedLocation> locations = new ArrayList<SuggestedLocation>();

			XmlPullUtil.enter(pp, "itdStopFinderRequest");

			processItdOdv(pp, "sf", new ProcessItdOdvCallback()
			{
				public void location(final String nameState, final Location location, final int matchQuality)
				{
					locations.add(new SuggestedLocation(location, matchQuality));
				}
			});

			XmlPullUtil.skipExit(pp, "itdStopFinderRequest");

			return new SuggestLocationsResult(header, locations);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	protected SuggestLocationsResult mobileStopfinderRequest(final Location constraint) throws IOException
	{
		final StringBuilder parameters = stopfinderRequestParameters(constraint, "XML");

		final StringBuilder uri = new StringBuilder(stopFinderEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterEfa(pp);

			final List<SuggestedLocation> locations = new ArrayList<SuggestedLocation>();

			XmlPullUtil.require(pp, "sf");
			if (!pp.isEmptyElementTag())
			{
				XmlPullUtil.enter(pp, "sf");

				while (XmlPullUtil.test(pp, "p"))
				{
					XmlPullUtil.enter(pp, "p");

					final String name = normalizeLocationName(XmlPullUtil.valueTag(pp, "n"));
					final String u = XmlPullUtil.valueTag(pp, "u");
					if (!"sf".equals(u))
						throw new RuntimeException("unknown usage: " + u);
					final String ty = XmlPullUtil.valueTag(pp, "ty");
					final LocationType type;
					if ("stop".equals(ty))
						type = LocationType.STATION;
					else if ("poi".equals(ty))
						type = LocationType.POI;
					else if ("loc".equals(ty))
						type = LocationType.ADDRESS;
					else if ("street".equals(ty))
						type = LocationType.ADDRESS;
					else if ("singlehouse".equals(ty))
						type = LocationType.ADDRESS;
					else
						throw new RuntimeException("unknown type: " + ty);

					XmlPullUtil.enter(pp, "r");

					final String id = XmlPullUtil.valueTag(pp, "id");
					XmlPullUtil.valueTag(pp, "stateless");
					XmlPullUtil.valueTag(pp, "omc");
					final String place = normalizeLocationName(XmlPullUtil.optValueTag(pp, "pc", null));
					XmlPullUtil.valueTag(pp, "pid");
					final Point coord = parseCoord(XmlPullUtil.optValueTag(pp, "c", null));

					XmlPullUtil.skipExit(pp, "r");

					final String qal = XmlPullUtil.optValueTag(pp, "qal", null);
					final int quality = qal != null ? Integer.parseInt(qal) : 0;

					XmlPullUtil.skipExit(pp, "p");

					final Location location = new Location(type, type == LocationType.STATION ? id : null, coord, place, name);
					final SuggestedLocation locationAndQuality = new SuggestedLocation(location, quality);
					locations.add(locationAndQuality);
				}

				XmlPullUtil.skipExit(pp, "sf");
			}
			else
			{
				XmlPullUtil.next(pp);
			}

			return new SuggestLocationsResult(header, locations);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	private StringBuilder xmlCoordRequestParameters(final EnumSet<LocationType> types, final int lat, final int lon, final int maxDistance,
			final int maxLocations)
	{
		final StringBuilder parameters = new StringBuilder();
		appendCommonRequestParams(parameters, "XML");
		parameters.append("&coord=").append(String.format(Locale.ENGLISH, "%2.6f:%2.6f:WGS84", latLonToDouble(lon), latLonToDouble(lat)));
		if (useStringCoordListOutputFormat)
			parameters.append("&coordListOutputFormat=STRING");
		parameters.append("&max=").append(maxLocations != 0 ? maxLocations : 50);
		parameters.append("&inclFilter=1");
		int i = 1;
		for (final LocationType type : types)
		{
			parameters.append("&radius_").append(i).append('=').append(maxDistance != 0 ? maxDistance : 1320);
			parameters.append("&type_").append(i).append('=');
			if (type == LocationType.STATION)
				parameters.append("STOP");
			else if (type == LocationType.POI)
				parameters.append("POI_POINT");
			else
				throw new IllegalArgumentException("cannot handle location type: " + type); // ENTRANCE, BUS_POINT
			i++;
		}

		return parameters;
	}

	protected NearbyLocationsResult xmlCoordRequest(final EnumSet<LocationType> types, final int lat, final int lon, final int maxDistance,
			final int maxStations) throws IOException
	{
		final StringBuilder parameters = xmlCoordRequestParameters(types, lat, lon, maxDistance, maxStations);

		final StringBuilder uri = new StringBuilder(coordEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterItdRequest(pp);

			XmlPullUtil.enter(pp, "itdCoordInfoRequest");

			XmlPullUtil.enter(pp, "itdCoordInfo");

			XmlPullUtil.enter(pp, "coordInfoRequest");
			XmlPullUtil.skipExit(pp, "coordInfoRequest");

			final List<Location> locations = new ArrayList<Location>();

			if (XmlPullUtil.test(pp, "coordInfoItemList"))
			{
				XmlPullUtil.enter(pp, "coordInfoItemList");

				while (XmlPullUtil.test(pp, "coordInfoItem"))
				{
					final String type = XmlPullUtil.attr(pp, "type");
					final LocationType locationType;
					if ("STOP".equals(type))
						locationType = LocationType.STATION;
					else if ("POI_POINT".equals(type))
						locationType = LocationType.POI;
					else
						throw new IllegalStateException("unknown type: " + type);

					String id = XmlPullUtil.optAttr(pp, "stateless", null);
					if (id == null)
						id = XmlPullUtil.attr(pp, "id");

					final String name = normalizeLocationName(XmlPullUtil.optAttr(pp, "name", null));
					final String place = normalizeLocationName(XmlPullUtil.optAttr(pp, "locality", null));

					XmlPullUtil.enter(pp, "coordInfoItem");

					// FIXME this is always only one coordinate
					final Point coord = processItdPathCoordinates(pp).get(0);

					XmlPullUtil.skipExit(pp, "coordInfoItem");

					if (name != null)
						locations.add(new Location(locationType, id, coord, place, name));
				}

				XmlPullUtil.skipExit(pp, "coordInfoItemList");
			}

			return new NearbyLocationsResult(header, locations);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	protected NearbyLocationsResult mobileCoordRequest(final EnumSet<LocationType> types, final int lat, final int lon, final int maxDistance,
			final int maxStations) throws IOException
	{
		final StringBuilder parameters = xmlCoordRequestParameters(types, lat, lon, maxDistance, maxStations);

		final StringBuilder uri = new StringBuilder(coordEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterEfa(pp);

			XmlPullUtil.enter(pp, "ci");

			XmlPullUtil.enter(pp, "request");
			XmlPullUtil.skipExit(pp, "request");

			final List<Location> stations = new ArrayList<Location>();

			if (XmlPullUtil.test(pp, "pis"))
			{
				XmlPullUtil.enter(pp, "pis");

				while (XmlPullUtil.test(pp, "pi"))
				{
					XmlPullUtil.enter(pp, "pi");

					final String name = normalizeLocationName(XmlPullUtil.valueTag(pp, "de"));
					final String type = XmlPullUtil.valueTag(pp, "ty");
					final LocationType locationType;
					if ("STOP".equals(type))
						locationType = LocationType.STATION;
					else if ("POI_POINT".equals(type))
						locationType = LocationType.POI;
					else
						throw new IllegalStateException("unknown type: " + type);

					final String id = XmlPullUtil.valueTag(pp, "id");
					XmlPullUtil.valueTag(pp, "omc");
					XmlPullUtil.valueTag(pp, "pid");
					final String place = normalizeLocationName(XmlPullUtil.valueTag(pp, "locality"));
					XmlPullUtil.valueTag(pp, "layer");
					XmlPullUtil.valueTag(pp, "gisID");
					XmlPullUtil.valueTag(pp, "ds");
					final Point coord = parseCoord(XmlPullUtil.valueTag(pp, "c"));

					stations.add(new Location(locationType, id, coord, place, name));

					XmlPullUtil.skipExit(pp, "pi");
				}

				XmlPullUtil.skipExit(pp, "pis");
			}

			XmlPullUtil.skipExit(pp, "ci");

			return new NearbyLocationsResult(header, stations);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	public SuggestLocationsResult suggestLocations(final CharSequence constraint) throws IOException
	{
		return jsonStopfinderRequest(new Location(LocationType.ANY, null, null, constraint.toString()));
	}

	private interface ProcessItdOdvCallback
	{
		void location(String nameState, Location location, int matchQuality);
	}

	private String processItdOdv(final XmlPullParser pp, final String expectedUsage, final ProcessItdOdvCallback callback)
			throws XmlPullParserException, IOException
	{
		if (!XmlPullUtil.test(pp, "itdOdv"))
			throw new IllegalStateException("expecting <itdOdv />");

		final String usage = XmlPullUtil.attr(pp, "usage");
		if (expectedUsage != null && !usage.equals(expectedUsage))
			throw new IllegalStateException("expecting <itdOdv usage=\"" + expectedUsage + "\" />");

		final String type = XmlPullUtil.attr(pp, "type");

		XmlPullUtil.enter(pp, "itdOdv");

		final String place = processItdOdvPlace(pp);

		XmlPullUtil.require(pp, "itdOdvName");
		final String nameState = XmlPullUtil.attr(pp, "state");
		XmlPullUtil.enter(pp, "itdOdvName");

		XmlPullUtil.optSkip(pp, "itdMessage");

		if ("identified".equals(nameState))
		{
			final Location location = processOdvNameElem(pp, type, place);
			if (location != null)
				callback.location(nameState, location, Integer.MAX_VALUE);
		}
		else if ("list".equals(nameState))
		{
			while (XmlPullUtil.test(pp, "odvNameElem"))
			{
				final int matchQuality = XmlPullUtil.intAttr(pp, "matchQuality");
				final Location location = processOdvNameElem(pp, type, place);
				if (location != null)
					callback.location(nameState, location, matchQuality);
			}
		}
		else if ("notidentified".equals(nameState) || "empty".equals(nameState))
		{
			XmlPullUtil.optSkip(pp, "odvNameElem");
		}
		else
		{
			throw new RuntimeException("cannot handle nameState '" + nameState + "'");
		}

		while (XmlPullUtil.test(pp, "infoLink"))
			XmlPullUtil.requireSkip(pp, "infoLink");

		XmlPullUtil.optSkip(pp, "odvNameInput");

		XmlPullUtil.exit(pp, "itdOdvName");

		XmlPullUtil.optSkip(pp, "odvInfoList");

		XmlPullUtil.optSkip(pp, "itdPoiHierarchyRoot");

		if (XmlPullUtil.test(pp, "itdOdvAssignedStops"))
		{
			XmlPullUtil.enter(pp, "itdOdvAssignedStops");

			while (XmlPullUtil.test(pp, "itdOdvAssignedStop"))
			{
				final Location stop = processItdOdvAssignedStop(pp);

				if (stop != null)
					callback.location("assigned", stop, 0);
			}

			XmlPullUtil.exit(pp, "itdOdvAssignedStops");
		}

		XmlPullUtil.optSkip(pp, "itdServingModes");

		XmlPullUtil.optSkip(pp, "genAttrList");

		XmlPullUtil.exit(pp, "itdOdv");

		return nameState;
	}

	private String processItdOdvPlace(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		if (!XmlPullUtil.test(pp, "itdOdvPlace"))
			throw new IllegalStateException("expecting <itdOdvPlace />");

		final String placeState = XmlPullUtil.attr(pp, "state");

		XmlPullUtil.enter(pp, "itdOdvPlace");
		String place = null;
		if ("identified".equals(placeState))
		{
			if (XmlPullUtil.test(pp, "odvPlaceElem"))
				place = normalizeLocationName(XmlPullUtil.valueTag(pp, "odvPlaceElem"));
		}
		XmlPullUtil.skipExit(pp, "itdOdvPlace");

		return place;
	}

	private Location processOdvNameElem(final XmlPullParser pp, String type, final String defaultPlace) throws XmlPullParserException, IOException
	{
		if (!XmlPullUtil.test(pp, "odvNameElem"))
			throw new IllegalStateException("expecting <odvNameElem />");

		if ("any".equals(type))
			type = XmlPullUtil.attr(pp, "anyType");
		final String id = XmlPullUtil.attr(pp, "stateless");
		final String locality = normalizeLocationName(XmlPullUtil.optAttr(pp, "locality", null));
		final String objectName = normalizeLocationName(XmlPullUtil.optAttr(pp, "objectName", null));
		final String buildingName = XmlPullUtil.optAttr(pp, "buildingName", null);
		final String buildingNumber = XmlPullUtil.optAttr(pp, "buildingNumber", null);
		final String postCode = XmlPullUtil.optAttr(pp, "postCode", null);
		final Point coord = processCoordAttr(pp);

		final String nameElem = normalizeLocationName(XmlPullUtil.valueTag(pp, "odvNameElem"));

		final LocationType locationType;
		final String place;
		final String name;

		if ("stop".equals(type))
		{
			locationType = LocationType.STATION;
			place = locality;
			name = objectName;
		}
		else if ("poi".equals(type))
		{
			locationType = LocationType.POI;
			place = locality;
			name = objectName;
		}
		else if ("loc".equals(type))
		{
			return null;
		}
		else if ("address".equals(type) || "singlehouse".equals(type))
		{
			locationType = LocationType.ADDRESS;
			place = locality;
			name = objectName + (buildingNumber != null ? " " + buildingNumber : "");
		}
		else if ("street".equals(type) || "crossing".equals(type))
		{
			locationType = LocationType.ADDRESS;
			place = locality;
			name = objectName;
		}
		else if ("postcode".equals(type))
		{
			locationType = LocationType.ADDRESS;
			place = locality;
			name = postCode;
		}
		else if ("buildingname".equals(type))
		{
			locationType = LocationType.ADDRESS;
			place = locality;
			name = buildingName;
		}
		else if ("coord".equals(type))
		{
			locationType = LocationType.ADDRESS;
			place = defaultPlace;
			name = nameElem;
		}
		else
		{
			throw new IllegalArgumentException("unknown type/anyType: " + type);
		}

		return new Location(locationType, id, coord, place != null ? place : defaultPlace, name != null ? name : nameElem);
	}

	private Location processItdOdvAssignedStop(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		final String id = XmlPullUtil.attr(pp, "stopID");
		final Point coord = processCoordAttr(pp);
		final String place = normalizeLocationName(XmlPullUtil.optAttr(pp, "place", null));
		final String name = normalizeLocationName(XmlPullUtil.optValueTag(pp, "itdOdvAssignedStop", null));

		if (name != null)
			return new Location(LocationType.STATION, id, coord, place, name);
		else
			return null;
	}

	public NearbyLocationsResult queryNearbyLocations(final EnumSet<LocationType> types, final Location location, final int maxDistance,
			final int maxLocations) throws IOException
	{
		if (location.hasLocation())
			return xmlCoordRequest(types, location.lat, location.lon, maxDistance, maxLocations);

		if (location.type != LocationType.STATION)
			throw new IllegalArgumentException("cannot handle: " + location.type);

		if (!location.hasId())
			throw new IllegalArgumentException("at least one of stationId or lat/lon must be given");

		return nearbyStationsRequest(location.id, maxLocations);
	}

	private NearbyLocationsResult nearbyStationsRequest(final String stationId, final int maxLocations) throws IOException
	{
		final StringBuilder parameters = new StringBuilder();
		appendCommonRequestParams(parameters, "XML");
		parameters.append("&type_dm=stop&name_dm=").append(normalizeStationId(stationId));
		parameters.append("&itOptionsActive=1");
		parameters.append("&ptOptionsActive=1");
		if (useProxFootSearch)
			parameters.append("&useProxFootSearch=1");
		parameters.append("&mergeDep=1");
		parameters.append("&useAllStops=1");
		parameters.append("&mode=direct");

		final StringBuilder uri = new StringBuilder(departureMonitorEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterItdRequest(pp);

			XmlPullUtil.enter(pp, "itdDepartureMonitorRequest");

			final AtomicReference<Location> ownStation = new AtomicReference<Location>();
			final List<Location> stations = new ArrayList<Location>();

			final String nameState = processItdOdv(pp, "dm", new ProcessItdOdvCallback()
			{
				public void location(final String nameState, final Location location, final int matchQuality)
				{
					if (location.type == LocationType.STATION)
					{
						if ("identified".equals(nameState))
							ownStation.set(location);
						else if ("assigned".equals(nameState))
							stations.add(location);
					}
					else
					{
						throw new IllegalStateException("cannot handle: " + location.type);
					}
				}
			});

			if ("notidentified".equals(nameState))
				return new NearbyLocationsResult(header, NearbyLocationsResult.Status.INVALID_ID);

			if (ownStation.get() != null && !stations.contains(ownStation))
				stations.add(ownStation.get());

			if (maxLocations == 0 || maxLocations >= stations.size())
				return new NearbyLocationsResult(header, stations);
			else
				return new NearbyLocationsResult(header, stations.subList(0, maxLocations));
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	private static final Pattern P_LINE_RE = Pattern.compile("RE ?\\d+");
	private static final Pattern P_LINE_RB = Pattern.compile("RB ?\\d+");
	private static final Pattern P_LINE_R = Pattern.compile("R ?\\d+");
	private static final Pattern P_LINE_S = Pattern.compile("S ?\\d+");
	private static final Pattern P_LINE_NUMBER = Pattern.compile("\\d+");

	protected String parseLine(final String mot, String symbol, final String name, final String longName, final String trainType,
			final String trainNum, final String trainName)
	{
		if (mot == null)
		{
			if (trainName != null)
			{
				final String str = name != null ? name : "";
				if (trainName.equals("S-Bahn"))
					return 'S' + str;
				if (trainName.equals("U-Bahn"))
					return 'U' + str;
				if (trainName.equals("Straßenbahn"))
					return 'T' + str;
				if (trainName.equals("Badner Bahn"))
					return 'T' + str;
				if (trainName.equals("Stadtbus"))
					return 'B' + str;
				if (trainName.equals("Citybus"))
					return 'B' + str;
				if (trainName.equals("Regionalbus"))
					return 'B' + str;
				if (trainName.equals("ÖBB-Postbus"))
					return 'B' + str;
				if (trainName.equals("Autobus"))
					return 'B' + str;
				if (trainName.equals("Discobus"))
					return 'B' + str;
				if (trainName.equals("Nachtbus"))
					return 'B' + str;
				if (trainName.equals("Anrufsammeltaxi"))
					return 'B' + str;
				if (trainName.equals("Ersatzverkehr"))
					return 'B' + str;
				if (trainName.equals("Vienna Airport Lines"))
					return 'B' + str;
			}

			throw new IllegalStateException("cannot normalize mot='" + mot + "' symbol='" + symbol + "' name='" + name + "' long='" + longName
					+ "' trainType='" + trainType + "' trainNum='" + trainNum + "' trainName='" + trainName + "'");
		}
		else if ("0".equals(mot))
		{
			final String trainNumStr = trainNum != null ? trainNum : "";

			if (("EC".equals(trainType) || "EuroCity".equals(trainName) || "Eurocity".equals(trainName)) && trainNum != null)
				return "IEC" + trainNum;
			if (("EN".equals(trainType) || "EuroNight".equals(trainName)) && trainNum != null)
				return "IEN" + trainNum;
			if (("IC".equals(trainType) || "InterCity".equals(trainName)) && trainNum != null)
				return "IIC" + trainNum;
			if (("ICE".equals(trainType) || "ICE".equals(trainName) || "Intercity-Express".equals(trainName)) && trainNum != null)
				return "IICE" + trainNum;
			if (("ICN".equals(trainType) || "InterCityNight".equals(trainName)) && trainNum != null)
				return "IICN" + trainNum;
			if (("X".equals(trainType) || "InterConnex".equals(trainName)) && trainNum != null)
				return "IX" + trainNum;
			if (("CNL".equals(trainType) || "CityNightLine".equals(trainName)) && trainNum != null) // City Night Line
				return "ICNL" + trainNum;
			if (("THA".equals(trainType) || "Thalys".equals(trainName)) && trainNum != null)
				return "ITHA" + trainNum;
			if ("RHI".equals(trainType) && trainNum != null)
				return "IRHI" + trainNum;
			if (("TGV".equals(trainType) || "TGV".equals(trainName)) && trainNum != null)
				return "ITGV" + trainNum;
			if ("TGD".equals(trainType) && trainNum != null)
				return "ITGD" + trainNum;
			if ("INZ".equals(trainType) && trainNum != null)
				return "IINZ" + trainNum;
			if (("RJ".equals(trainType) || "railjet".equals(trainName)) && trainNum != null) // railjet
				return "IRJ" + trainNum;
			if (("WB".equals(trainType) || "WESTbahn".equals(trainName)) && trainNum != null)
				return "IWB" + trainNum;
			if (("HKX".equals(trainType) || "Hamburg-Köln-Express".equals(trainName)) && trainNum != null)
				return "IHKX" + trainNum;
			if ("INT".equals(trainType) && trainNum != null) // SVV, VAGFR
				return "IINT" + trainNum;
			if (("SC".equals(trainType) || "SC Pendolino".equals(trainName)) && trainNum != null) // SuperCity
				return "ISC" + trainNum;
			if ("ECB".equals(trainType) && trainNum != null) // EC, Verona-München
				return "IECB" + trainNum;
			if ("ES".equals(trainType) && trainNum != null) // Eurostar Italia
				return "IES" + trainNum;
			if (("EST".equals(trainType) || "EUROSTAR".equals(trainName)) && trainNum != null)
				return "IEST" + trainNum;
			if ("EIC".equals(trainType) && trainNum != null) // Ekspres InterCity, Polen
				return "IEIC" + trainNum;
			if ("MT".equals(trainType) && "Schnee-Express".equals(trainName) && trainNum != null)
				return "IMT" + trainNum;
			if (("TLK".equals(trainType) || "Tanie Linie Kolejowe".equals(trainName)) && trainNum != null)
				return "ITLK" + trainNum;

			if ("Zug".equals(trainName))
				return 'R' + symbol;
			if ("Zuglinie".equals(trainName))
				return 'R' + symbol;
			if ("IR".equals(trainType) || "Interregio".equals(trainName) || "InterRegio".equals(trainName))
				return "RIR" + trainNum;
			if ("IRE".equals(trainType) || "Interregio-Express".equals(trainName))
				return "RIRE" + trainNum;
			if ("InterRegioExpress".equals(trainName))
				return "RIRE" + trainNumStr;
			if ("RE".equals(trainType) || "Regional-Express".equals(trainName))
				return "RRE" + trainNum;
			if (trainType == null && trainNum != null && P_LINE_RE.matcher(trainNum).matches())
				return 'R' + trainNum;
			if ("Regionalexpress".equals(trainName))
				return 'R' + symbol;
			if ("R-Bahn".equals(trainName))
				return 'R' + symbol;
			if ("RB-Bahn".equals(trainName))
				return 'R' + symbol;
			if ("RE-Bahn".equals(trainName))
				return 'R' + symbol;
			if ("REX".equals(trainType)) // RegionalExpress, Österreich
				return "RREX" + trainNum;
			if (("RB".equals(trainType) || "Regionalbahn".equals(trainName)) && trainNum != null)
				return "RRB" + trainNum;
			if (trainType == null && trainNum != null && P_LINE_RB.matcher(trainNum).matches())
				return 'R' + trainNum;
			if ("Abellio-Zug".equals(trainName))
				return "R" + symbol;
			if ("Westfalenbahn".equals(trainName))
				return 'R' + symbol;
			if ("Chiemseebahn".equals(trainName))
				return 'R' + symbol;
			if ("R".equals(trainType) || "Regionalzug".equals(trainName))
				return "RR" + trainNum;
			if (trainType == null && trainNum != null && P_LINE_R.matcher(trainNum).matches())
				return 'R' + trainNum;
			if ("D".equals(trainType) || "Schnellzug".equals(trainName))
				return "RD" + trainNum;
			if ("E".equals(trainType) || "Eilzug".equals(trainName))
				return "RE" + trainNum;
			if ("WFB".equals(trainType) || "WestfalenBahn".equals(trainName))
				return "RWFB" + trainNum;
			if (("NWB".equals(trainType) || "NordWestBahn".equals(trainName)) && trainNum != null)
				return "RNWB" + trainNum;
			if ("WES".equals(trainType) || "Westbahn".equals(trainName))
				return "RWES" + trainNum;
			if ("ERB".equals(trainType) || "eurobahn".equals(trainName))
				return "RERB" + trainNum;
			if ("CAN".equals(trainType) || "cantus Verkehrsgesellschaft".equals(trainName))
				return "RCAN" + trainNum;
			if ("HEX".equals(trainType) || "Veolia Verkehr Sachsen-Anhalt".equals(trainName))
				return "RHEX" + trainNum;
			if ("EB".equals(trainType) || "Erfurter Bahn".equals(trainName))
				return "REB" + trainNum;
			if ("Erfurter Bahn".equals(longName))
				return "REB";
			if ("EBx".equals(trainType) || "Erfurter Bahn Express".equals(trainName))
				return "REBx" + trainNum;
			if ("Erfurter Bahn Express".equals(longName))
				return "REBx";
			if ("MRB".equals(trainType) || "Mitteldeutsche Regiobahn".equals(trainName))
				return "RMRB" + trainNum;
			if ("ABR".equals(trainType) || "ABELLIO Rail NRW GmbH".equals(trainName))
				return "RABR" + trainNum;
			if ("NEB".equals(trainType) || "NEB Niederbarnimer Eisenbahn".equals(trainName))
				return "RNEB" + trainNum;
			if ("OE".equals(trainType) || "Ostdeutsche Eisenbahn GmbH".equals(trainName))
				return "ROE" + trainNum;
			if ("ODE".equals(trainType))
				return 'R' + symbol;
			if ("OLA".equals(trainType) || "Ostseeland Verkehr GmbH".equals(trainName))
				return "ROLA" + trainNum;
			if ("UBB".equals(trainType) || "Usedomer Bäderbahn".equals(trainName))
				return "RUBB" + trainNum;
			if ("EVB".equals(trainType) || "ELBE-WESER GmbH".equals(trainName))
				return "REVB" + trainNum;
			if ("RTB".equals(trainType) || "Rurtalbahn GmbH".equals(trainName))
				return "RRTB" + trainNum;
			if ("STB".equals(trainType) || "Süd-Thüringen-Bahn".equals(trainName))
				return "RSTB" + trainNum;
			if ("HTB".equals(trainType) || "Hellertalbahn".equals(trainName))
				return "RHTB" + trainNum;
			if ("VBG".equals(trainType) || "Vogtlandbahn".equals(trainName))
				return "RVBG" + trainNum;
			if ("CB".equals(trainType) || "City-Bahn Chemnitz".equals(trainName))
				return "RCB" + trainNum;
			if ("VEC".equals(trainType) || "vectus Verkehrsgesellschaft".equals(trainName))
				return "RVEC" + trainNum;
			if ("HzL".equals(trainType) || "Hohenzollerische Landesbahn AG".equals(trainName))
				return "RHzL" + trainNum;
			if ("SBB".equals(trainType) || "SBB GmbH".equals(trainName))
				return "RSBB" + trainNum;
			if ("MBB".equals(trainType) || "Mecklenburgische Bäderbahn Molli".equals(trainName))
				return "RMBB" + trainNum;
			if ("OS".equals(trainType)) // Osobní vlak
				return "ROS" + trainNum;
			if ("SP".equals(trainType) || "Sp".equals(trainType)) // Spěšný vlak
				return "RSP" + trainNum;
			if ("Dab".equals(trainType) || "Daadetalbahn".equals(trainName))
				return "RDab" + trainNum;
			if ("FEG".equals(trainType) || "Freiberger Eisenbahngesellschaft".equals(trainName))
				return "RFEG" + trainNum;
			if ("ARR".equals(trainType) || "ARRIVA".equals(trainName))
				return "RARR" + trainNum;
			if ("HSB".equals(trainType) || "Harzer Schmalspurbahn".equals(trainName))
				return "RHSB" + trainNum;
			if ("ALX".equals(trainType) || "alex - Länderbahn und Vogtlandbahn GmbH".equals(trainName))
				return "RALX" + trainNum;
			if ("EX".equals(trainType) || "Fatra".equals(trainName))
				return "REX" + trainNum;
			if ("ME".equals(trainType) || "metronom".equals(trainName))
				return "RME" + trainNum;
			if ("metronom".equals(longName))
				return "RME";
			if ("MEr".equals(trainType))
				return "RMEr" + trainNum;
			if ("AKN".equals(trainType) || "AKN Eisenbahn AG".equals(trainName))
				return "RAKN" + trainNum;
			if ("SOE".equals(trainType) || "Sächsisch-Oberlausitzer Eisenbahngesellschaft".equals(trainName))
				return "RSOE" + trainNum;
			if ("VIA".equals(trainType) || "VIAS GmbH".equals(trainName))
				return "RVIA" + trainNum;
			if ("BRB".equals(trainType) || "Bayerische Regiobahn".equals(trainName))
				return "RBRB" + trainNum;
			if ("BLB".equals(trainType) || "Berchtesgadener Land Bahn".equals(trainName))
				return "RBLB" + trainNum;
			if ("HLB".equals(trainType) || "Hessische Landesbahn".equals(trainName))
				return "RHLB" + trainNum;
			if ("NOB".equals(trainType) || "NordOstseeBahn".equals(trainName))
				return "RNOB" + trainNum;
			if ("NBE".equals(trainType) || "Nordbahn Eisenbahngesellschaft".equals(trainName))
				return "RNBE" + trainNum;
			if ("VEN".equals(trainType) || "Rhenus Veniro".equals(trainName))
				return "RVEN" + trainType;
			if ("DPN".equals(trainType) || "Nahreisezug".equals(trainName))
				return "RDPN" + trainNum;
			if ("RBG".equals(trainType) || "Regental Bahnbetriebs GmbH".equals(trainName))
				return "RRBG" + trainNum;
			if ("BOB".equals(trainType) || "Bodensee-Oberschwaben-Bahn".equals(trainName))
				return "RBOB" + trainNum;
			if ("VE".equals(trainType) || "Vetter".equals(trainName))
				return "RVE" + trainNum;
			if ("SDG".equals(trainType) || "SDG Sächsische Dampfeisenbahngesellschaft mbH".equals(trainName))
				return "RSDG" + trainNum;
			if ("PRE".equals(trainType) || "Pressnitztalbahn".equals(trainName))
				return "RPRE" + trainNum;
			if ("VEB".equals(trainType) || "Vulkan-Eifel-Bahn".equals(trainName))
				return "RVEB" + trainNum;
			if ("neg".equals(trainType) || "Norddeutsche Eisenbahn Gesellschaft".equals(trainName))
				return "Rneg" + trainNum;
			if ("AVG".equals(trainType) || "Felsenland-Express".equals(trainName))
				return "RAVG" + trainNum;
			if ("P".equals(trainType) || "BayernBahn Betriebs-GmbH".equals(trainName) || "Brohltalbahn".equals(trainName)
					|| "Kasbachtalbahn".equals(trainName))
				return "RP" + trainNum;
			if ("SBS".equals(trainType) || "Städtebahn Sachsen".equals(trainName))
				return "RSBS" + trainNum;
			if ("SES".equals(trainType) || "Städteexpress Sachsen".equals(trainName))
				return "RSES" + trainNum;
			if ("SB-".equals(trainType)) // Städtebahn Sachsen
				return "RSB" + trainNum;
			if ("ag".equals(trainType)) // agilis
				return "Rag" + trainNum;
			if ("agi".equals(trainType) || "agilis".equals(trainName))
				return "Ragi" + trainNum;
			if ("as".equals(trainType) || "agilis-Schnellzug".equals(trainName))
				return "Ras" + trainNum;
			if ("TLX".equals(trainType) || "TRILEX".equals(trainName)) // Trilex (Vogtlandbahn)
				return "RTLX" + trainNum;
			if ("MSB".equals(trainType) || "Mainschleifenbahn".equals(trainName))
				return "RMSB" + trainNum;
			if ("BE".equals(trainType) || "Bentheimer Eisenbahn".equals(trainName))
				return "RBE" + trainNum;
			if ("erx".equals(trainType) || "erixx - Der Heidesprinter".equals(trainName))
				return "Rerx" + trainNum;
			if ("SWEG-Zug".equals(trainName)) // Südwestdeutschen Verkehrs-Aktiengesellschaft
				return "RSWEG" + trainNum;
			if ("SWEG-Zug".equals(longName))
				return "RSWEG";
			if ("EGP Eisenbahngesellschaft Potsdam".equals(trainName))
				return "REGP" + trainNumStr;
			if ("ÖBB".equals(trainType) || "ÖBB".equals(trainName))
				return "RÖBB" + trainNum;
			if ("CAT".equals(trainType)) // City Airport Train Wien
				return "RCAT" + trainNum;
			if ("DZ".equals(trainType) || "Dampfzug".equals(trainName))
				return "RDZ" + trainNum;
			if ("CD".equals(trainType)) // Tschechien
				return "RCD" + trainNum;
			if ("VR".equals(trainType)) // Polen
				return 'R' + symbol;
			if ("PR".equals(trainType)) // Polen
				return 'R' + symbol;
			if ("KD".equals(trainType)) // Koleje Dolnośląskie (Niederschlesische Eisenbahn)
				return 'R' + symbol;
			if ("Koleje Dolnoslaskie".equals(trainName) && symbol != null) // Koleje Dolnośląskie
				return "R" + symbol;
			if ("OO".equals(trainType) || "Ordinary passenger (o.pas.)".equals(trainName)) // GB
				return "ROO" + trainNum;
			if ("XX".equals(trainType) || "Express passenger    (ex.pas.)".equals(trainName)) // GB
				return "RXX" + trainNum;
			if ("XZ".equals(trainType) || "Express passenger sleeper".equals(trainName)) // GB
				return "RXZ" + trainNum;
			if ("ATB".equals(trainType)) // Autoschleuse Tauernbahn
				return "RATB" + trainNum;
			if ("ATZ".equals(trainType)) // Autozug
				return "RATZ" + trainNum;
			if ("AZ".equals(trainType) || "Auto-Zug".equals(trainName))
				return "RAZ" + trainNum;
			if ("DWE".equals(trainType) || "Dessau-Wörlitzer Eisenbahn".equals(trainName))
				return "RDWE" + trainNum;
			if ("KTB".equals(trainType) || "Kandertalbahn".equals(trainName))
				return "RKTB" + trainNum;
			if ("CBC".equals(trainType) || "CBC".equals(trainName)) // City-Bahn Chemnitz
				return "RCBC" + trainNum;
			if ("Bernina Express".equals(trainName))
				return 'R' + trainNum;
			if ("STR".equals(trainType)) // Harzquerbahn, Nordhausen
				return "RSTR" + trainNum;
			if ("EXT".equals(trainType) || "Extrazug".equals(trainName))
				return "REXT" + trainNum;
			if ("Heritage Railway".equals(trainName)) // GB
				return 'R' + symbol;
			if ("WTB".equals(trainType) || "Wutachtalbahn".equals(trainName))
				return "RWTB" + trainNum;
			if ("DB".equals(trainType) || "DB Regio".equals(trainName))
				return "RDB" + trainNum;
			if ("M".equals(trainType) && "Meridian".equals(trainName))
				return "RM" + trainNum;
			if ("M".equals(trainType) && "Messezug".equals(trainName))
				return "RM" + trainNum;
			if ("EZ".equals(trainType)) // ÖBB Erlebniszug
				return "REZ" + trainNum;
			if ("DPF".equals(trainType))
				return "RDPF" + trainNum;
			if ("WBA".equals(trainType) || "Waldbahn".equals(trainName))
				return "RWBA" + trainNum;
			if ("ÖBA".equals(trainType) && trainNum != null) // Eisenbahn-Betriebsgesellschaft Ochsenhausen
				return "RÖBA" + trainNum;
			if (("UEF".equals(trainType) || "Ulmer Eisenbahnfreunde".equals(trainName)) && trainNum != null)
				return "RUEF" + trainNum;
			if (("DBG".equals(trainType) || "Döllnitzbahn".equals(trainName)) && trainNum != null)
				return "RDBG" + trainNum;
			if (("TL".equals(trainType) || "Trilex".equals(trainName)) && trainNum != null)
				return "RTL" + trainNum;
			if (("OPB".equals(trainType) || "oberpfalzbahn".equals(trainName)) && trainNum != null)
				return "ROPB" + trainNum;
			if (("OPX".equals(trainType) || "oberpfalz-express".equals(trainName)) && trainNum != null)
				return "ROPX" + trainNum;
			if (("V6".equals(trainType) || "vlexx".equals(trainName)) && trainNum != null)
				return "Rvlexx" + trainNum;

			if ("BSB-Zug".equals(trainName) && trainNum != null) // Breisgau-S-Bahn
				return 'S' + trainNum;
			if ("BSB-Zug".equals(trainName) && trainNum == null)
				return "SBSB";
			if ("BSB-Zug".equals(longName))
				return "SBSB";
			if ("RSB".equals(trainType)) // Regionalschnellbahn, Wien
				return "SRSB" + trainNum;
			if ("RER".equals(trainName) && symbol != null && symbol.length() == 1) // Réseau Express Régional,
																					// Frankreich
				return 'S' + symbol;
			if ("S".equals(trainType))
				return "SS" + trainNum;
			if ("S-Bahn".equals(trainName))
				return "SS" + trainNumStr;

			if ("RT".equals(trainType) || "RegioTram".equals(trainName))
				return "TRT" + trainNum;

			if ("Bus".equals(trainType))
				return "B" + trainNum;
			if ("SEV".equals(trainType) || "SEV".equals(trainNum) || "SEV".equals(trainName) || "SEV".equals(symbol) || "BSV".equals(trainType)
					|| "Ersatzverkehr".equals(trainName) || "Schienenersatzverkehr".equals(trainName))
				return "BSEV" + (trainNum != null ? trainNum : "");
			if ("Bus replacement".equals(trainName)) // GB
				return "BBR";
			if ("BR".equals(trainType) && trainName.startsWith("Bus")) // GB
				return "BBR" + trainNum;

			if ("GB".equals(trainType)) // Gondelbahn
				return "CGB" + trainNum;
			if ("SB".equals(trainType)) // Seilbahn
				return "CSB" + trainNum;

			if ("ZUG".equals(trainType) && trainNum != null)
				return '?' + trainNum;
			if (symbol != null && P_LINE_NUMBER.matcher(symbol).matches() && trainType == null && trainName == null)
				return '?' + symbol;
			if ("N".equals(trainType) && trainName == null && symbol == null)
				return "?N" + trainNum;
			if ("Train".equals(trainName))
				return "?";

			// generic
			if (trainName != null && trainType == null && trainNum == null)
				return '?' + trainName;

			throw new IllegalStateException("cannot normalize mot='" + mot + "' symbol='" + symbol + "' name='" + name + "' long='" + longName
					+ "' trainType='" + trainType + "' trainNum='" + trainNum + "' trainName='" + trainName + "'");
		}
		else if ("1".equals(mot))
		{
			if (symbol != null && P_LINE_S.matcher(symbol).matches())
				return "S" + symbol;
			if (name != null && P_LINE_S.matcher(name).matches())
				return "S" + name;
			if ("S-Bahn".equals(trainName) && trainNum == null)
				return "SS";
		}
		else if ("2".equals(mot))
		{
			return 'U' + name;
		}
		else if ("3".equals(mot) || "4".equals(mot))
		{
			return 'T' + name;
		}
		else if ("5".equals(mot) || "6".equals(mot) || "7".equals(mot) || "10".equals(mot))
		{
			if ("Schienenersatzverkehr".equals(name))
				return "BSEV";
			else
				return 'B' + name;
		}
		else if ("8".equals(mot))
		{
			return 'C' + name;
		}
		else if ("9".equals(mot))
		{
			return 'F' + name;
		}
		else if ("11".equals(mot))
		{
			return '?' + ParserUtils.firstNotEmpty(symbol, name);
		}

		throw new IllegalStateException("cannot normalize mot='" + mot + "' symbol='" + symbol + "' name='" + name + "' long='" + longName
				+ "' trainType='" + trainType + "' trainNum='" + trainNum + "' trainName='" + trainName + "'");
	}

	public QueryDeparturesResult queryDepartures(final String stationId, final Date time, final int maxDepartures, final boolean equivs)
			throws IOException
	{
		return xsltDepartureMonitorRequest(stationId, time, maxDepartures, equivs);
	}

	protected StringBuilder xsltDepartureMonitorRequestParameters(final String stationId, final Date time, final int maxDepartures,
			final boolean equivs)
	{
		final StringBuilder parameters = new StringBuilder();
		appendCommonRequestParams(parameters, "XML");
		parameters.append("&type_dm=stop");
		parameters.append("&name_dm=").append(normalizeStationId(stationId));
		if (time != null)
			appendItdDateTimeParameters(parameters, time);
		if (useRealtime)
			parameters.append("&useRealtime=1");
		parameters.append("&mode=direct");
		parameters.append("&ptOptionsActive=1");
		parameters.append("&deleteAssignedStops_dm=").append(equivs ? '0' : '1');
		if (useProxFootSearch)
			parameters.append("&useProxFootSearch=").append(equivs ? '1' : '0');
		parameters.append("&mergeDep=1"); // merge departures
		if (maxDepartures > 0)
			parameters.append("&limit=").append(maxDepartures);

		return parameters;
	}

	private final void appendItdDateTimeParameters(final StringBuilder uri, final Date time)
	{
		final Calendar c = new GregorianCalendar(timeZone);
		c.setTime(time);
		final int year = c.get(Calendar.YEAR);
		final int month = c.get(Calendar.MONTH) + 1;
		final int day = c.get(Calendar.DAY_OF_MONTH);
		final int hour = c.get(Calendar.HOUR_OF_DAY);
		final int minute = c.get(Calendar.MINUTE);
		uri.append("&itdDate=").append(ParserUtils.urlEncode(String.format(Locale.ENGLISH, "%04d%02d%02d", year, month, day)));
		uri.append("&itdTime=").append(ParserUtils.urlEncode(String.format(Locale.ENGLISH, "%02d%02d", hour, minute)));
	}

	private QueryDeparturesResult xsltDepartureMonitorRequest(final String stationId, final Date time, final int maxDepartures, final boolean equivs)
			throws IOException
	{
		final StringBuilder parameters = xsltDepartureMonitorRequestParameters(stationId, time, maxDepartures, equivs);

		final StringBuilder uri = new StringBuilder(departureMonitorEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterItdRequest(pp);

			final QueryDeparturesResult result = new QueryDeparturesResult(header);

			XmlPullUtil.enter(pp, "itdDepartureMonitorRequest");

			XmlPullUtil.optSkip(pp, "itdMessage");

			final String nameState = processItdOdv(pp, "dm", new ProcessItdOdvCallback()
			{
				public void location(final String nameState, final Location location, final int matchQuality)
				{
					if (location.type == LocationType.STATION)
						if (findStationDepartures(result.stationDepartures, location.id) == null)
							result.stationDepartures.add(new StationDepartures(location, new LinkedList<Departure>(),
									new LinkedList<LineDestination>()));
				}
			});

			if ("notidentified".equals(nameState) || "list".equals(nameState))
				return new QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION);

			XmlPullUtil.optSkip(pp, "itdDateTime");

			XmlPullUtil.optSkip(pp, "itdDMDateTime");

			XmlPullUtil.optSkip(pp, "itdDateRange");

			XmlPullUtil.optSkip(pp, "itdTripOptions");

			XmlPullUtil.optSkip(pp, "itdMessage");

			final Calendar plannedDepartureTime = new GregorianCalendar(timeZone);
			final Calendar predictedDepartureTime = new GregorianCalendar(timeZone);

			XmlPullUtil.require(pp, "itdServingLines");
			if (!pp.isEmptyElementTag())
			{
				XmlPullUtil.enter(pp, "itdServingLines");
				while (XmlPullUtil.test(pp, "itdServingLine"))
				{
					final String assignedStopId = XmlPullUtil.optAttr(pp, "assignedStopID", null);
					final String destinationName = normalizeLocationName(XmlPullUtil.attr(pp, "direction"));
					final String destinationId = XmlPullUtil.optAttr(pp, "destID", null);
					final Location destination = new Location(destinationId != null ? LocationType.STATION : LocationType.ANY, destinationId, null,
							destinationName);
					final LineDestination line = new LineDestination(processItdServingLine(pp), destination);

					StationDepartures assignedStationDepartures;
					if (assignedStopId == null)
						assignedStationDepartures = result.stationDepartures.get(0);
					else
						assignedStationDepartures = findStationDepartures(result.stationDepartures, assignedStopId);

					if (assignedStationDepartures == null)
						assignedStationDepartures = new StationDepartures(new Location(LocationType.STATION, assignedStopId),
								new LinkedList<Departure>(), new LinkedList<LineDestination>());

					if (!assignedStationDepartures.lines.contains(line))
						assignedStationDepartures.lines.add(line);
				}
				XmlPullUtil.skipExit(pp, "itdServingLines");
			}
			else
			{
				XmlPullUtil.next(pp);
			}

			XmlPullUtil.require(pp, "itdDepartureList");
			if (!pp.isEmptyElementTag())
			{
				XmlPullUtil.enter(pp, "itdDepartureList");
				while (XmlPullUtil.test(pp, "itdDeparture"))
				{
					final String assignedStopId = XmlPullUtil.attr(pp, "stopID");

					StationDepartures assignedStationDepartures = findStationDepartures(result.stationDepartures, assignedStopId);
					if (assignedStationDepartures == null)
					{
						final Point coord = processCoordAttr(pp);

						// final String name = normalizeLocationName(XmlPullUtil.attr(pp, "nameWO"));

						assignedStationDepartures = new StationDepartures(new Location(LocationType.STATION, assignedStopId, coord),
								new LinkedList<Departure>(), new LinkedList<LineDestination>());
					}

					final Position position = parsePosition(XmlPullUtil.optAttr(pp, "platformName", null));

					XmlPullUtil.enter(pp, "itdDeparture");

					XmlPullUtil.require(pp, "itdDateTime");
					plannedDepartureTime.clear();
					processItdDateTime(pp, plannedDepartureTime);

					predictedDepartureTime.clear();
					if (XmlPullUtil.test(pp, "itdRTDateTime"))
						processItdDateTime(pp, predictedDepartureTime);

					if (XmlPullUtil.test(pp, "itdFrequencyInfo"))
						XmlPullUtil.next(pp);

					XmlPullUtil.require(pp, "itdServingLine");
					final boolean isRealtime = XmlPullUtil.attr(pp, "realtime").equals("1");
					final String destinationName = normalizeLocationName(XmlPullUtil.attr(pp, "direction"));
					final String destinationIdStr = XmlPullUtil.optAttr(pp, "destID", null);
					final String destinationId = !"-1".equals(destinationIdStr) ? destinationIdStr : null;
					final Location destination = new Location(destinationId != null ? LocationType.STATION : LocationType.ANY, destinationId, null,
							destinationName);
					final Line line = processItdServingLine(pp);

					if (isRealtime && !predictedDepartureTime.isSet(Calendar.HOUR_OF_DAY))
						predictedDepartureTime.setTimeInMillis(plannedDepartureTime.getTimeInMillis());

					XmlPullUtil.skipExit(pp, "itdDeparture");

					final Departure departure = new Departure(plannedDepartureTime.getTime(),
							predictedDepartureTime.isSet(Calendar.HOUR_OF_DAY) ? predictedDepartureTime.getTime() : null, line, position,
							destination, null, null);
					assignedStationDepartures.departures.add(departure);
				}

				XmlPullUtil.skipExit(pp, "itdDepartureList");
			}
			else
			{
				XmlPullUtil.next(pp);
			}

			return result;
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	protected QueryDeparturesResult queryDeparturesMobile(final String stationId, final Date time, final int maxDepartures, final boolean equivs)
			throws IOException
	{
		final StringBuilder parameters = xsltDepartureMonitorRequestParameters(stationId, time, maxDepartures, equivs);

		final StringBuilder uri = new StringBuilder(departureMonitorEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpReferer, null);
			firstChars = ParserUtils.peekFirstChars(is);

			final XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(is, null);
			final ResultHeader header = enterEfa(pp);
			final QueryDeparturesResult result = new QueryDeparturesResult(header);

			XmlPullUtil.require(pp, "dps");
			if (!pp.isEmptyElementTag())
			{
				XmlPullUtil.enter(pp, "dps");

				final Calendar plannedDepartureTime = new GregorianCalendar(timeZone);
				final Calendar predictedDepartureTime = new GregorianCalendar(timeZone);

				while (XmlPullUtil.test(pp, "dp"))
				{
					XmlPullUtil.enter(pp, "dp");

					// misc
					/* final String stationName = */normalizeLocationName(XmlPullUtil.valueTag(pp, "n"));
					final boolean isRealtime = XmlPullUtil.valueTag(pp, "realtime").equals("1");

					XmlPullUtil.optSkip(pp, "dt");

					// time
					parseMobileSt(pp, plannedDepartureTime, predictedDepartureTime);

					final LineDestination lineDestination = parseMobileM(pp, true);

					XmlPullUtil.enter(pp, "r");
					final String assignedId = XmlPullUtil.valueTag(pp, "id");
					XmlPullUtil.valueTag(pp, "a");
					final Position position = super.parsePosition(XmlPullUtil.optValueTag(pp, "pl", null));
					XmlPullUtil.skipExit(pp, "r");

					/* final Point positionCoordinate = */parseCoord(XmlPullUtil.optValueTag(pp, "c", null));

					// TODO messages

					StationDepartures stationDepartures = findStationDepartures(result.stationDepartures, assignedId);
					if (stationDepartures == null)
					{
						stationDepartures = new StationDepartures(new Location(LocationType.STATION, assignedId), new ArrayList<Departure>(
								maxDepartures), null);
						result.stationDepartures.add(stationDepartures);
					}

					stationDepartures.departures.add(new Departure(plannedDepartureTime.getTime(),
							predictedDepartureTime.isSet(Calendar.HOUR_OF_DAY) ? predictedDepartureTime.getTime() : null, lineDestination.line,
							position, lineDestination.destination, null, null));

					XmlPullUtil.skipExit(pp, "dp");
				}

				XmlPullUtil.skipExit(pp, "dps");

				return result;
			}
			else
			{
				return new QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION);
			}
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	private static final Pattern P_MOBILE_M_SYMBOL = Pattern.compile("([^\\s]*)\\s+([^\\s]*)");

	private LineDestination parseMobileM(final XmlPullParser pp, final boolean tyOrCo) throws XmlPullParserException, IOException
	{
		XmlPullUtil.enter(pp, "m");

		final String n = XmlPullUtil.optValueTag(pp, "n", null);
		final String productNu = XmlPullUtil.valueTag(pp, "nu");
		final String ty = XmlPullUtil.valueTag(pp, "ty");

		final Line line;
		final Location destination;
		if ("100".equals(ty) || "99".equals(ty))
		{
			destination = null;
			line = Line.FOOTWAY;
		}
		else if ("105".equals(ty))
		{
			destination = null;
			line = Line.TRANSFER;
		}
		else if ("98".equals(ty))
		{
			destination = null;
			line = Line.SECURE_CONNECTION;
		}
		else if ("97".equals(ty))
		{
			destination = null;
			line = Line.DO_NOT_CHANGE;
		}
		else
		{
			final String co = XmlPullUtil.valueTag(pp, "co");
			final String productType = tyOrCo ? ty : co;
			final String destinationName = normalizeLocationName(XmlPullUtil.valueTag(pp, "des"));
			destination = new Location(LocationType.ANY, null, null, destinationName);
			XmlPullUtil.optValueTag(pp, "dy", null);
			final String de = XmlPullUtil.optValueTag(pp, "de", null);
			final String productName = n != null ? n : de;
			final String lineId = parseMobileDv(pp);

			final String symbol = productNu.endsWith(" " + productName) ? productNu.substring(0, productNu.length() - productName.length() - 1)
					: productNu;
			final String trainType;
			final String trainNum;
			final Matcher mSymbol = P_MOBILE_M_SYMBOL.matcher(symbol);
			if (mSymbol.matches())
			{
				trainType = mSymbol.group(1);
				trainNum = mSymbol.group(2);
			}
			else
			{
				trainType = null;
				trainNum = null;
			}

			final String network = lineId.substring(0, lineId.indexOf(':'));
			final String lineLabel = parseLine(productType, symbol, symbol, null, trainType, trainNum, productName);
			line = new Line(lineId, lineLabel, lineStyle(network, lineLabel));
		}

		XmlPullUtil.skipExit(pp, "m");

		return new LineDestination(line, destination);
	}

	private String parseMobileDv(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		XmlPullUtil.enter(pp, "dv");
		XmlPullUtil.optValueTag(pp, "branch", null);
		final String lineIdLi = XmlPullUtil.valueTag(pp, "li");
		final String lineIdSu = XmlPullUtil.valueTag(pp, "su");
		final String lineIdPr = XmlPullUtil.valueTag(pp, "pr");
		final String lineIdDct = XmlPullUtil.valueTag(pp, "dct");
		final String lineIdNe = XmlPullUtil.valueTag(pp, "ne");
		XmlPullUtil.skipExit(pp, "dv");

		return lineIdNe + ":" + lineIdLi + ":" + lineIdSu + ":" + lineIdDct + ":" + lineIdPr;
	}

	private void parseMobileSt(final XmlPullParser pp, final Calendar plannedDepartureTime, final Calendar predictedDepartureTime)
			throws XmlPullParserException, IOException
	{
		XmlPullUtil.enter(pp, "st");

		plannedDepartureTime.clear();
		ParserUtils.parseIsoDate(plannedDepartureTime, XmlPullUtil.valueTag(pp, "da"));
		ParserUtils.parseIsoTime(plannedDepartureTime, XmlPullUtil.valueTag(pp, "t"));

		predictedDepartureTime.clear();
		if (XmlPullUtil.test(pp, "rda"))
		{
			ParserUtils.parseIsoDate(predictedDepartureTime, XmlPullUtil.valueTag(pp, "rda"));
			ParserUtils.parseIsoTime(predictedDepartureTime, XmlPullUtil.valueTag(pp, "rt"));
		}

		XmlPullUtil.skipExit(pp, "st");
	}

	private StationDepartures findStationDepartures(final List<StationDepartures> stationDepartures, final String id)
	{
		for (final StationDepartures stationDeparture : stationDepartures)
			if (stationDeparture.location.id.equals(id))
				return stationDeparture;

		return null;
	}

	private Location processItdPointAttributes(final XmlPullParser pp)
	{
		final String id = XmlPullUtil.attr(pp, "stopID");

		String place = normalizeLocationName(XmlPullUtil.optAttr(pp, "locality", null));
		if (place == null)
			place = normalizeLocationName(XmlPullUtil.optAttr(pp, "place", null));

		String name = normalizeLocationName(XmlPullUtil.optAttr(pp, "nameWO", null));
		if (name == null)
			name = normalizeLocationName(XmlPullUtil.optAttr(pp, "name", null));

		final Point coord = processCoordAttr(pp);

		return new Location(LocationType.STATION, id, coord, place, name);
	}

	private boolean processItdDateTime(final XmlPullParser pp, final Calendar calendar) throws XmlPullParserException, IOException
	{
		XmlPullUtil.enter(pp);
		calendar.clear();
		final boolean success = processItdDate(pp, calendar);
		if (success)
			processItdTime(pp, calendar);
		XmlPullUtil.skipExit(pp);

		return success;
	}

	private boolean processItdDate(final XmlPullParser pp, final Calendar calendar) throws XmlPullParserException, IOException
	{
		XmlPullUtil.require(pp, "itdDate");
		final int year = XmlPullUtil.intAttr(pp, "year");
		final int month = XmlPullUtil.intAttr(pp, "month") - 1;
		final int day = XmlPullUtil.intAttr(pp, "day");
		final int weekday = XmlPullUtil.intAttr(pp, "weekday");
		XmlPullUtil.next(pp);

		if (weekday < 0)
			return false;
		if (year == 0)
			return false;
		if (year < 1900 || year > 2100)
			throw new InvalidDataException("invalid year: " + year);
		if (month < 0 || month > 11)
			throw new InvalidDataException("invalid month: " + month);
		if (day < 1 || day > 31)
			throw new InvalidDataException("invalid day: " + day);

		calendar.set(Calendar.YEAR, year);
		calendar.set(Calendar.MONTH, month);
		calendar.set(Calendar.DAY_OF_MONTH, day);
		return true;
	}

	private void processItdTime(final XmlPullParser pp, final Calendar calendar) throws XmlPullParserException, IOException
	{
		XmlPullUtil.require(pp, "itdTime");
		calendar.set(Calendar.HOUR_OF_DAY, XmlPullUtil.intAttr(pp, "hour"));
		calendar.set(Calendar.MINUTE, XmlPullUtil.intAttr(pp, "minute"));
		XmlPullUtil.next(pp);
	}

	private Line processItdServingLine(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		XmlPullUtil.require(pp, "itdServingLine");
		final String slMotType = XmlPullUtil.attr(pp, "motType");
		final String slSymbol = XmlPullUtil.optAttr(pp, "symbol", null);
		final String slNumber = XmlPullUtil.optAttr(pp, "number", null);
		final String slStateless = XmlPullUtil.optAttr(pp, "stateless", null);
		final String slTrainType = XmlPullUtil.optAttr(pp, "trainType", null);
		final String slTrainName = XmlPullUtil.optAttr(pp, "trainName", null);
		final String slTrainNum = XmlPullUtil.optAttr(pp, "trainNum", null);

		XmlPullUtil.enter(pp, "itdServingLine");
		String itdTrainName = null;
		String itdTrainType = null;
		String itdMessage = null;
		if (XmlPullUtil.test(pp, "itdTrain"))
		{
			itdTrainName = XmlPullUtil.attr(pp, "name");
			itdTrainType = XmlPullUtil.attr(pp, "type");
			if (!pp.isEmptyElementTag())
			{
				XmlPullUtil.enter(pp, "itdTrain");
				XmlPullUtil.skipExit(pp, "itdTrain");
			}
			else
			{
				XmlPullUtil.next(pp);
			}
		}
		if (XmlPullUtil.test(pp, "itdNoTrain"))
		{
			itdTrainName = XmlPullUtil.optAttr(pp, "name", null);
			itdTrainType = XmlPullUtil.optAttr(pp, "type", null);
			if (!pp.isEmptyElementTag())
			{
				final String text = XmlPullUtil.valueTag(pp, "itdNoTrain");
				if (itdTrainName != null && itdTrainName.toLowerCase().contains("ruf"))
					itdMessage = text;
				else if (text != null && text.toLowerCase().contains("ruf"))
					itdMessage = text;
			}
			else
			{
				XmlPullUtil.next(pp);
			}
		}

		XmlPullUtil.require(pp, "motDivaParams");
		final String divaNetwork = XmlPullUtil.optAttr(pp, "network", null);

		XmlPullUtil.skipExit(pp, "itdServingLine");

		final String trainType = ParserUtils.firstNotEmpty(slTrainType, itdTrainType);
		final String trainName = ParserUtils.firstNotEmpty(slTrainName, itdTrainName);

		final String label = parseLine(slMotType, slSymbol, slNumber, slNumber, trainType, slTrainNum, trainName);

		return new Line(slStateless, label, lineStyle(divaNetwork, label), itdMessage);
	}

	private static final Pattern P_STATION_NAME_WHITESPACE = Pattern.compile("\\s+");

	protected String normalizeLocationName(final String name)
	{
		if (name == null || name.length() == 0)
			return null;

		return P_STATION_NAME_WHITESPACE.matcher(name).replaceAll(" ");
	}

	protected static double latLonToDouble(final int value)
	{
		return (double) value / 1000000;
	}

	protected String xsltTripRequestParameters(final Location from, final Location via, final Location to, final Date time, final boolean dep,
			final Collection<Product> products, final WalkSpeed walkSpeed, final Accessibility accessibility, final Set<Option> options)
	{
		final StringBuilder uri = new StringBuilder();
		appendCommonRequestParams(uri, "XML");

		uri.append("&sessionID=0");
		uri.append("&requestID=0");

		appendCommonXsltTripRequest2Params(uri);

		appendLocation(uri, from, "origin");
		appendLocation(uri, to, "destination");
		if (via != null)
			appendLocation(uri, via, "via");

		appendItdDateTimeParameters(uri, time);

		uri.append("&itdTripDateTimeDepArr=").append(dep ? "dep" : "arr");

		uri.append("&calcNumberOfTrips=").append(numTripsRequested);

		uri.append("&ptOptionsActive=1"); // enable public transport options
		uri.append("&itOptionsActive=1"); // enable individual transport options
		uri.append("&changeSpeed=").append(WALKSPEED_MAP.get(walkSpeed));

		if (accessibility == Accessibility.BARRIER_FREE)
			uri.append("&imparedOptionsActive=1").append("&wheelchair=on").append("&noSolidStairs=on");
		else if (accessibility == Accessibility.LIMITED)
			uri.append("&imparedOptionsActive=1").append("&wheelchair=on").append("&lowPlatformVhcl=on").append("&noSolidStairs=on");

		if (products != null)
		{
			uri.append("&includedMeans=checkbox");

			boolean hasI = false;
			for (final Product p : products)
			{
				if (p == Product.HIGH_SPEED_TRAIN || p == Product.REGIONAL_TRAIN)
				{
					uri.append("&inclMOT_0=on");
					if (p == Product.HIGH_SPEED_TRAIN)
						hasI = true;
				}

				if (p == Product.SUBURBAN_TRAIN)
					uri.append("&inclMOT_1=on");

				if (p == Product.SUBWAY)
					uri.append("&inclMOT_2=on");

				if (p == Product.TRAM)
					uri.append("&inclMOT_3=on&inclMOT_4=on");

				if (p == Product.BUS)
					uri.append("&inclMOT_5=on&inclMOT_6=on&inclMOT_7=on");

				if (p == Product.ON_DEMAND)
					uri.append("&inclMOT_10=on");

				if (p == Product.FERRY)
					uri.append("&inclMOT_9=on");

				if (p == Product.CABLECAR)
					uri.append("&inclMOT_8=on");
			}

			uri.append("&inclMOT_11=on"); // TODO always show 'others', for now

			// workaround for highspeed trains: fails when you want highspeed, but not regional
			if (useLineRestriction && !hasI)
				uri.append("&lineRestriction=403"); // means: all but ice
		}

		if (options != null && options.contains(Option.BIKE))
			uri.append("&bikeTakeAlong=1");

		uri.append("&locationServerActive=1");
		if (useRealtime)
			uri.append("&useRealtime=1");
		if (useProxFootSearch)
			uri.append("&useProxFootSearch=1"); // walk if it makes journeys quicker
		uri.append("&nextDepsPerLeg=1"); // next departure in case previous was missed

		return uri.toString();
	}

	private String commandLink(final String sessionId, final String requestId)
	{
		final StringBuilder uri = new StringBuilder(tripEndpoint);

		uri.append("?sessionID=").append(sessionId);
		uri.append("&requestID=").append(requestId);
		uri.append("&calcNumberOfTrips=").append(numTripsRequested);
		appendCommonXsltTripRequest2Params(uri);

		return uri.toString();
	}

	private final void appendCommonXsltTripRequest2Params(final StringBuilder uri)
	{
		if (useStringCoordListOutputFormat)
			uri.append("&coordListOutputFormat=STRING");
	}

	public QueryTripsResult queryTrips(final Location from, final Location via, final Location to, final Date date, final boolean dep,
			final Set<Product> products, final WalkSpeed walkSpeed, final Accessibility accessibility, final Set<Option> options) throws IOException
	{

		final String parameters = xsltTripRequestParameters(from, via, to, date, dep, products, walkSpeed, accessibility, options);

		final StringBuilder uri = new StringBuilder(tripEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpRefererTrip, sessionCookieName);
			firstChars = ParserUtils.peekFirstChars(is);

			return queryTrips(uri.toString(), is);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		catch (final RuntimeException x)
		{
			throw new RuntimeException("uncategorized problem while processing " + uri, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	protected QueryTripsResult queryTripsMobile(final Location from, final Location via, final Location to, final Date date, final boolean dep,
			final Collection<Product> products, final WalkSpeed walkSpeed, final Accessibility accessibility, final Set<Option> options)
			throws IOException
	{

		final String parameters = xsltTripRequestParameters(from, via, to, date, dep, products, walkSpeed, accessibility, options);

		final StringBuilder uri = new StringBuilder(tripEndpoint);
		if (!httpPost)
			uri.append(parameters);

		// System.out.println(uri);
		// System.out.println(parameters);

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), httpPost ? parameters.substring(1) : null, null, httpRefererTrip, sessionCookieName);
			firstChars = ParserUtils.peekFirstChars(is);

			return queryTripsMobile(uri.toString(), from, via, to, is);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		catch (final RuntimeException x)
		{
			throw new RuntimeException("uncategorized problem while processing " + uri, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	public QueryTripsResult queryMoreTrips(final QueryTripsContext contextObj, final boolean later) throws IOException
	{
		final Context context = (Context) contextObj;
		final String commandUri = context.context;
		final StringBuilder uri = new StringBuilder(commandUri);
		uri.append("&command=").append(later ? "tripNext" : "tripPrev");

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), null, null, httpRefererTrip, sessionCookieName);
			firstChars = ParserUtils.peekFirstChars(is);

			return queryTrips(uri.toString(), is);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		catch (final RuntimeException x)
		{
			throw new RuntimeException("uncategorized problem while processing " + uri, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	protected QueryTripsResult queryMoreTripsMobile(final QueryTripsContext contextObj, final boolean later) throws IOException
	{
		final Context context = (Context) contextObj;
		final String commandUri = context.context;
		final StringBuilder uri = new StringBuilder(commandUri);
		uri.append("&command=").append(later ? "tripNext" : "tripPrev");

		InputStream is = null;
		String firstChars = null;

		try
		{
			is = ParserUtils.scrapeInputStream(uri.toString(), null, null, httpRefererTrip, sessionCookieName);
			firstChars = ParserUtils.peekFirstChars(is);
			is.mark(512);

			return queryTripsMobile(uri.toString(), null, null, null, is);
		}
		catch (final XmlPullParserException x)
		{
			throw new ParserException("cannot parse xml: " + firstChars, x);
		}
		catch (final RuntimeException x)
		{
			throw new RuntimeException("uncategorized problem while processing " + uri, x);
		}
		finally
		{
			if (is != null)
				is.close();
		}
	}

	private QueryTripsResult queryTrips(final String uri, final InputStream is) throws XmlPullParserException, IOException
	{
		// System.out.println(uri);

		final XmlPullParser pp = parserFactory.newPullParser();
		pp.setInput(is, null);
		final ResultHeader header = enterItdRequest(pp);
		final Object context = header.context;

		if (XmlPullUtil.test(pp, "itdLayoutParams"))
			XmlPullUtil.next(pp);

		XmlPullUtil.require(pp, "itdTripRequest");
		final String requestId = XmlPullUtil.attr(pp, "requestID");
		XmlPullUtil.enter(pp, "itdTripRequest");

		if (XmlPullUtil.test(pp, "itdMessage"))
		{
			final int code = XmlPullUtil.intAttr(pp, "code");
			if (code == -4000) // no trips
				return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
			XmlPullUtil.next(pp);
		}
		if (XmlPullUtil.test(pp, "itdPrintConfiguration"))
			XmlPullUtil.next(pp);
		if (XmlPullUtil.test(pp, "itdAddress"))
			XmlPullUtil.next(pp);

		List<Location> ambiguousFrom = null, ambiguousTo = null, ambiguousVia = null;
		Location from = null, via = null, to = null;

		while (XmlPullUtil.test(pp, "itdOdv"))
		{
			final String usage = XmlPullUtil.attr(pp, "usage");

			final List<Location> locations = new ArrayList<Location>();
			final String nameState = processItdOdv(pp, usage, new ProcessItdOdvCallback()
			{
				public void location(final String nameState, final Location location, final int matchQuality)
				{
					locations.add(location);
				}
			});

			if ("list".equals(nameState))
			{
				if ("origin".equals(usage))
					ambiguousFrom = locations;
				else if ("via".equals(usage))
					ambiguousVia = locations;
				else if ("destination".equals(usage))
					ambiguousTo = locations;
				else
					throw new IllegalStateException("unknown usage: " + usage);
			}
			else if ("identified".equals(nameState))
			{
				if ("origin".equals(usage))
					from = locations.get(0);
				else if ("via".equals(usage))
					via = locations.get(0);
				else if ("destination".equals(usage))
					to = locations.get(0);
				else
					throw new IllegalStateException("unknown usage: " + usage);
			}
			else if ("notidentified".equals(nameState))
			{
				if ("origin".equals(usage))
					return new QueryTripsResult(header, QueryTripsResult.Status.UNKNOWN_FROM);
				else if ("via".equals(usage))
					return new QueryTripsResult(header, QueryTripsResult.Status.UNKNOWN_VIA);
				else if ("destination".equals(usage))
					return new QueryTripsResult(header, QueryTripsResult.Status.UNKNOWN_TO);
				else
					throw new IllegalStateException("unknown usage: " + usage);
			}
		}

		if (ambiguousFrom != null || ambiguousTo != null || ambiguousVia != null)
			return new QueryTripsResult(header, ambiguousFrom, ambiguousVia, ambiguousTo);

		XmlPullUtil.enter(pp, "itdTripDateTime");
		XmlPullUtil.enter(pp, "itdDateTime");
		XmlPullUtil.require(pp, "itdDate");
		if (!pp.isEmptyElementTag())
		{
			XmlPullUtil.enter(pp, "itdDate");
			if (XmlPullUtil.test(pp, "itdMessage"))
			{
				final String message = XmlPullUtil.nextText(pp, null, "itdMessage");

				if ("invalid date".equals(message))
					return new QueryTripsResult(header, QueryTripsResult.Status.INVALID_DATE);
				else
					throw new IllegalStateException("unknown message: " + message);
			}
			XmlPullUtil.skipExit(pp, "itdDate");
		}
		else
		{
			XmlPullUtil.next(pp);
		}
		XmlPullUtil.skipExit(pp, "itdDateTime");
		XmlPullUtil.skipExit(pp, "itdTripDateTime");

		XmlPullUtil.requireSkip(pp, "itdTripOptions");

		final List<Trip> trips = new ArrayList<Trip>();

		XmlPullUtil.require(pp, "itdItinerary");
		if (!pp.isEmptyElementTag())
		{
			XmlPullUtil.enter(pp, "itdItinerary");

			XmlPullUtil.optSkip(pp, "itdLegTTs");

			if (XmlPullUtil.test(pp, "itdRouteList"))
			{
				XmlPullUtil.enter(pp, "itdRouteList");

				final Calendar time = new GregorianCalendar(timeZone);

				while (XmlPullUtil.test(pp, "itdRoute"))
				{
					final String id;
					if (useRouteIndexAsTripId)
					{
						final String routeIndex = XmlPullUtil.optAttr(pp, "routeIndex", null);
						final String routeTripIndex = XmlPullUtil.optAttr(pp, "routeTripIndex", null);
						if (routeIndex != null && routeTripIndex != null)
							id = routeIndex + "-" + routeTripIndex;
						else
							id = null;
					}
					else
					{
						id = null;
					}
					final int numChanges = XmlPullUtil.intAttr(pp, "changes");
					XmlPullUtil.enter(pp, "itdRoute");

					while (XmlPullUtil.test(pp, "itdDateTime"))
						XmlPullUtil.next(pp);
					if (XmlPullUtil.test(pp, "itdMapItemList"))
						XmlPullUtil.next(pp);

					XmlPullUtil.enter(pp, "itdPartialRouteList");
					final List<Trip.Leg> legs = new LinkedList<Trip.Leg>();
					Location firstDepartureLocation = null;
					Location lastArrivalLocation = null;

					boolean cancelled = false;

					while (XmlPullUtil.test(pp, "itdPartialRoute"))
					{
						final String partialRouteType = XmlPullUtil.attr(pp, "type");
						final int distance = XmlPullUtil.optIntAttr(pp, "distance", 0);
						XmlPullUtil.enter(pp, "itdPartialRoute");

						XmlPullUtil.test(pp, "itdPoint");
						if (!"departure".equals(XmlPullUtil.attr(pp, "usage")))
							throw new IllegalStateException();
						final Location departureLocation = processItdPointAttributes(pp);
						if (firstDepartureLocation == null)
							firstDepartureLocation = departureLocation;
						final Position departurePosition = parsePosition(XmlPullUtil.optAttr(pp, "platformName", null));
						XmlPullUtil.enter(pp, "itdPoint");
						if (XmlPullUtil.test(pp, "itdMapItemList"))
							XmlPullUtil.next(pp);
						XmlPullUtil.require(pp, "itdDateTime");
						processItdDateTime(pp, time);
						final Date departureTime = time.getTime();
						final Date departureTargetTime;
						if (XmlPullUtil.test(pp, "itdDateTimeTarget"))
						{
							processItdDateTime(pp, time);
							departureTargetTime = time.getTime();
						}
						else
						{
							departureTargetTime = null;
						}
						XmlPullUtil.skipExit(pp, "itdPoint");

						XmlPullUtil.test(pp, "itdPoint");
						if (!"arrival".equals(XmlPullUtil.attr(pp, "usage")))
							throw new IllegalStateException();
						final Location arrivalLocation = processItdPointAttributes(pp);
						lastArrivalLocation = arrivalLocation;
						final Position arrivalPosition = parsePosition(XmlPullUtil.optAttr(pp, "platformName", null));
						XmlPullUtil.enter(pp, "itdPoint");
						if (XmlPullUtil.test(pp, "itdMapItemList"))
							XmlPullUtil.next(pp);
						XmlPullUtil.require(pp, "itdDateTime");
						processItdDateTime(pp, time);
						final Date arrivalTime = time.getTime();
						final Date arrivalTargetTime;
						if (XmlPullUtil.test(pp, "itdDateTimeTarget"))
						{
							processItdDateTime(pp, time);
							arrivalTargetTime = time.getTime();
						}
						else
						{
							arrivalTargetTime = null;
						}
						XmlPullUtil.skipExit(pp, "itdPoint");

						XmlPullUtil.test(pp, "itdMeansOfTransport");
						final String productName = XmlPullUtil.optAttr(pp, "productName", null);
						if ("IT".equals(partialRouteType) || "Fussweg".equals(productName) || "Taxi".equals(productName))
						{
							final Trip.Individual.Type type = "Taxi".equals(productName) ? Trip.Individual.Type.TRANSFER : Trip.Individual.Type.WALK;

							XmlPullUtil.enter(pp, "itdMeansOfTransport");
							XmlPullUtil.skipExit(pp, "itdMeansOfTransport");

							if (XmlPullUtil.test(pp, "itdStopSeq"))
								XmlPullUtil.next(pp);

							if (XmlPullUtil.test(pp, "itdFootPathInfo"))
								XmlPullUtil.next(pp);

							List<Point> path = null;
							if (XmlPullUtil.test(pp, "itdPathCoordinates"))
								path = processItdPathCoordinates(pp);

							final Trip.Leg lastLeg = legs.size() > 0 ? legs.get(legs.size() - 1) : null;
							if (lastLeg != null && lastLeg instanceof Trip.Individual && ((Trip.Individual) lastLeg).type == type)
							{
								final Trip.Individual lastIndividual = (Trip.Individual) legs.remove(legs.size() - 1);
								if (path != null && lastIndividual.path != null)
									path.addAll(0, lastIndividual.path);
								legs.add(new Trip.Individual(type, lastIndividual.departure, lastIndividual.departureTime, arrivalLocation,
										arrivalTime, path, distance));
							}
							else
							{
								legs.add(new Trip.Individual(type, departureLocation, departureTime, arrivalLocation, arrivalTime, path, distance));
							}
						}
						else if ("gesicherter Anschluss".equals(productName) || "nicht umsteigen".equals(productName)) // type97
						{
							// ignore

							XmlPullUtil.enter(pp, "itdMeansOfTransport");
							XmlPullUtil.skipExit(pp, "itdMeansOfTransport");
						}
						else if ("PT".equals(partialRouteType))
						{
							final String destinationName = normalizeLocationName(XmlPullUtil.attr(pp, "destination"));
							final String destinationId = XmlPullUtil.optAttr(pp, "destID", null);
							final Location destination = new Location(destinationId != null ? LocationType.STATION : LocationType.ANY, destinationId,
									null, destinationName);
							final String lineLabel;
							final String motSymbol = XmlPullUtil.optAttr(pp, "symbol", null);
							if ("AST".equals(motSymbol))
							{
								lineLabel = "BAST";
							}
							else
							{
								final String motType = XmlPullUtil.attr(pp, "motType");
								final String motShortName = XmlPullUtil.optAttr(pp, "shortname", null);
								final String motName = XmlPullUtil.attr(pp, "name");
								final String motTrainName = XmlPullUtil.optAttr(pp, "trainName", null);
								final String motTrainType = XmlPullUtil.optAttr(pp, "trainType", null);

								lineLabel = parseLine(motType, motSymbol, motShortName, motName, motTrainType, motShortName, motTrainName);
							}
							XmlPullUtil.enter(pp, "itdMeansOfTransport");
							XmlPullUtil.require(pp, "motDivaParams");
							final String divaNetwork = XmlPullUtil.attr(pp, "network");
							final String divaLine = XmlPullUtil.attr(pp, "line");
							final String divaSupplement = XmlPullUtil.optAttr(pp, "supplement", "");
							final String divaDirection = XmlPullUtil.attr(pp, "direction");
							final String divaProject = XmlPullUtil.attr(pp, "project");
							final String lineId = divaNetwork + ':' + divaLine + ':' + divaSupplement + ':' + divaDirection + ':' + divaProject;
							XmlPullUtil.skipExit(pp, "itdMeansOfTransport");

							final Integer departureDelay;
							final Integer arrivalDelay;
							if (XmlPullUtil.test(pp, "itdRBLControlled"))
							{
								departureDelay = XmlPullUtil.optIntAttr(pp, "delayMinutes", 0);
								arrivalDelay = XmlPullUtil.optIntAttr(pp, "delayMinutesArr", 0);

								cancelled |= (departureDelay == -9999 || arrivalDelay == -9999);

								XmlPullUtil.next(pp);
							}
							else
							{
								departureDelay = null;
								arrivalDelay = null;
							}

							boolean lowFloorVehicle = false;
							String message = null;
							if (XmlPullUtil.test(pp, "itdInfoTextList"))
							{
								if (!pp.isEmptyElementTag())
								{
									XmlPullUtil.enter(pp, "itdInfoTextList");
									while (XmlPullUtil.test(pp, "infoTextListElem"))
									{
										final String text = XmlPullUtil.valueTag(pp, "infoTextListElem");
										if (text != null)
										{
											final String lcText = text.toLowerCase();
											if ("niederflurwagen soweit verfügbar".equals(lcText)) // KVV
												lowFloorVehicle = true;
											else if (lcText.contains("ruf") || lcText.contains("anmeld")) // Bedarfsverkehr
												message = text;
										}
									}
									XmlPullUtil.skipExit(pp, "itdInfoTextList");
								}
								else
								{
									XmlPullUtil.next(pp);
								}
							}

							XmlPullUtil.optSkip(pp, "itdFootPathInfo");

							while (XmlPullUtil.test(pp, "infoLink"))
							{
								XmlPullUtil.enter(pp, "infoLink");
								XmlPullUtil.optSkip(pp, "paramList");
								final String infoLinkText = XmlPullUtil.valueTag(pp, "infoLinkText");
								if (message == null)
									message = infoLinkText;
								XmlPullUtil.skipExit(pp, "infoLink");
							}

							List<Stop> intermediateStops = null;
							if (XmlPullUtil.test(pp, "itdStopSeq"))
							{
								XmlPullUtil.enter(pp, "itdStopSeq");
								intermediateStops = new LinkedList<Stop>();
								while (XmlPullUtil.test(pp, "itdPoint"))
								{
									final Location stopLocation = processItdPointAttributes(pp);

									final Position stopPosition = parsePosition(XmlPullUtil.optAttr(pp, "platformName", null));

									XmlPullUtil.enter(pp, "itdPoint");
									XmlPullUtil.require(pp, "itdDateTime");

									final Date plannedStopArrivalTime;
									final Date predictedStopArrivalTime;
									if (processItdDateTime(pp, time))
									{
										plannedStopArrivalTime = time.getTime();
										if (arrivalDelay != null)
										{
											time.add(Calendar.MINUTE, arrivalDelay);
											predictedStopArrivalTime = time.getTime();
										}
										else
										{
											predictedStopArrivalTime = null;
										}
									}
									else
									{
										plannedStopArrivalTime = null;
										predictedStopArrivalTime = null;
									}

									final Date plannedStopDepartureTime;
									final Date predictedStopDepartureTime;
									if (XmlPullUtil.test(pp, "itdDateTime") && processItdDateTime(pp, time))
									{
										plannedStopDepartureTime = time.getTime();
										if (departureDelay != null)
										{
											time.add(Calendar.MINUTE, departureDelay);
											predictedStopDepartureTime = time.getTime();
										}
										else
										{
											predictedStopDepartureTime = null;
										}
									}
									else
									{
										plannedStopDepartureTime = null;
										predictedStopDepartureTime = null;
									}

									final Stop stop = new Stop(stopLocation, plannedStopArrivalTime, predictedStopArrivalTime, stopPosition, null,
											plannedStopDepartureTime, predictedStopDepartureTime, stopPosition, null);

									intermediateStops.add(stop);

									XmlPullUtil.skipExit(pp, "itdPoint");
								}
								XmlPullUtil.skipExit(pp, "itdStopSeq");

								// remove first and last, because they are not intermediate
								final int size = intermediateStops.size();
								if (size >= 2)
								{
									if (!intermediateStops.get(size - 1).location.id.equals(arrivalLocation.id))
										throw new IllegalStateException();
									intermediateStops.remove(size - 1);

									if (!intermediateStops.get(0).location.id.equals(departureLocation.id))
										throw new IllegalStateException();
									intermediateStops.remove(0);
								}
							}

							List<Point> path = null;
							if (XmlPullUtil.test(pp, "itdPathCoordinates"))
								path = processItdPathCoordinates(pp);

							boolean wheelChairAccess = false;
							if (XmlPullUtil.test(pp, "genAttrList"))
							{
								XmlPullUtil.enter(pp, "genAttrList");
								while (XmlPullUtil.test(pp, "genAttrElem"))
								{
									XmlPullUtil.enter(pp, "genAttrElem");
									final String name = XmlPullUtil.valueTag(pp, "name");
									final String value = XmlPullUtil.valueTag(pp, "value");
									XmlPullUtil.skipExit(pp, "genAttrElem");

									// System.out.println("genAttrElem: name='" + name + "' value='" + value + "'");

									if ("PlanWheelChairAccess".equals(name) && "1".equals(value))
										wheelChairAccess = true;
								}
								XmlPullUtil.skipExit(pp, "genAttrList");
							}

							if (XmlPullUtil.test(pp, "nextDeps"))
							{
								XmlPullUtil.enter(pp, "nextDeps");
								while (XmlPullUtil.test(pp, "itdDateTime"))
								{
									processItdDateTime(pp, time);
									/* final Date nextDepartureTime = */time.getTime();
								}
								XmlPullUtil.skipExit(pp, "nextDeps");
							}

							final Set<Line.Attr> lineAttrs = new HashSet<Line.Attr>();
							if (wheelChairAccess || lowFloorVehicle)
								lineAttrs.add(Line.Attr.WHEEL_CHAIR_ACCESS);
							final Line line = new Line(lineId, lineLabel, lineStyle(divaNetwork, lineLabel), lineAttrs);

							final Stop departure = new Stop(departureLocation, true, departureTargetTime != null ? departureTargetTime
									: departureTime, departureTime != null ? departureTime : null, departurePosition, null);
							final Stop arrival = new Stop(arrivalLocation, false, arrivalTargetTime != null ? arrivalTargetTime : arrivalTime,
									arrivalTime != null ? arrivalTime : null, arrivalPosition, null);

							legs.add(new Trip.Public(line, destination, departure, arrival, intermediateStops, path, message));
						}
						else
						{
							throw new IllegalStateException("unknown type: '" + partialRouteType + "' '" + productName + "'");
						}

						XmlPullUtil.skipExit(pp, "itdPartialRoute");
					}

					XmlPullUtil.skipExit(pp, "itdPartialRouteList");

					final List<Fare> fares = new ArrayList<Fare>(2);
					if (XmlPullUtil.test(pp, "itdFare"))
					{
						if (!pp.isEmptyElementTag())
						{
							XmlPullUtil.enter(pp, "itdFare");
							if (XmlPullUtil.test(pp, "itdSingleTicket"))
							{
								final String net = XmlPullUtil.attr(pp, "net");
								final Currency currency = parseCurrency(XmlPullUtil.attr(pp, "currency"));
								final String fareAdult = XmlPullUtil.optAttr(pp, "fareAdult", null);
								final String fareChild = XmlPullUtil.optAttr(pp, "fareChild", null);
								final String unitName = XmlPullUtil.optAttr(pp, "unitName", null);
								final String unitsAdult = XmlPullUtil.optAttr(pp, "unitsAdult", null);
								final String unitsChild = XmlPullUtil.optAttr(pp, "unitsChild", null);
								final String levelAdult = XmlPullUtil.optAttr(pp, "levelAdult", null);
								final String levelChild = XmlPullUtil.optAttr(pp, "levelChild", null);
								if (fareAdult != null)
									fares.add(new Fare(net, Type.ADULT, currency, Float.parseFloat(fareAdult) * fareCorrectionFactor,
											levelAdult != null ? null : unitName, levelAdult != null ? levelAdult : unitsAdult));
								if (fareChild != null)
									fares.add(new Fare(net, Type.CHILD, currency, Float.parseFloat(fareChild) * fareCorrectionFactor,
											levelChild != null ? null : unitName, levelChild != null ? levelChild : unitsChild));

								if (!pp.isEmptyElementTag())
								{
									XmlPullUtil.enter(pp, "itdSingleTicket");
									if (XmlPullUtil.test(pp, "itdGenericTicketList"))
									{
										XmlPullUtil.enter(pp, "itdGenericTicketList");
										while (XmlPullUtil.test(pp, "itdGenericTicketGroup"))
										{
											final Fare fare = processItdGenericTicketGroup(pp, net, currency);
											if (fare != null)
												fares.add(fare);
										}
										XmlPullUtil.skipExit(pp, "itdGenericTicketList");
									}
									XmlPullUtil.skipExit(pp, "itdSingleTicket");
								}
								else
								{
									XmlPullUtil.next(pp);
								}
							}
							XmlPullUtil.skipExit(pp, "itdFare");
						}
						else
						{
							XmlPullUtil.next(pp);
						}
					}

					XmlPullUtil.skipExit(pp, "itdRoute");

					final Trip trip = new Trip(id, firstDepartureLocation, lastArrivalLocation, legs, fares.isEmpty() ? null : fares, null,
							numChanges);

					if (!cancelled)
						trips.add(trip);
				}

				XmlPullUtil.skipExit(pp, "itdRouteList");
			}
			XmlPullUtil.skipExit(pp, "itdItinerary");
		}
		else
		{
			XmlPullUtil.next(pp);
		}

		return new QueryTripsResult(header, uri, from, via, to, new Context(commandLink((String) context, requestId)), trips);
	}

	private QueryTripsResult queryTripsMobile(final String uri, final Location from, final Location via, final Location to, final InputStream is)
			throws XmlPullParserException, IOException
	{
		// System.out.println(uri);

		final XmlPullParser pp = parserFactory.newPullParser();
		pp.setInput(is, null);
		final ResultHeader header = enterEfa(pp);

		final Calendar plannedTime = new GregorianCalendar(timeZone);
		final Calendar predictedTime = new GregorianCalendar(timeZone);

		final List<Trip> trips = new ArrayList<Trip>();

		if (XmlPullUtil.test(pp, "ts"))
		{
			if (!pp.isEmptyElementTag())
			{
				XmlPullUtil.enter(pp, "ts");

				while (XmlPullUtil.test(pp, "tp"))
				{
					XmlPullUtil.enter(pp, "tp");

					XmlPullUtil.optSkip(pp, "attrs");

					XmlPullUtil.valueTag(pp, "d"); // duration
					final int numChanges = Integer.parseInt(XmlPullUtil.valueTag(pp, "ic"));
					final String tripId = XmlPullUtil.valueTag(pp, "de");

					XmlPullUtil.enter(pp, "ls");

					final List<Trip.Leg> legs = new LinkedList<Trip.Leg>();
					Location firstDepartureLocation = null;
					Location lastArrivalLocation = null;

					while (XmlPullUtil.test(pp, "l"))
					{
						XmlPullUtil.enter(pp, "l");

						XmlPullUtil.enter(pp, "ps");

						Stop departure = null;
						Stop arrival = null;

						while (XmlPullUtil.test(pp, "p"))
						{
							XmlPullUtil.enter(pp, "p");

							final String name = XmlPullUtil.valueTag(pp, "n");
							final String usage = XmlPullUtil.valueTag(pp, "u");
							XmlPullUtil.optValueTag(pp, "de", null);

							XmlPullUtil.requireSkip(pp, "dt");

							parseMobileSt(pp, plannedTime, predictedTime);

							XmlPullUtil.requireSkip(pp, "lis");

							XmlPullUtil.enter(pp, "r");
							final String id = XmlPullUtil.valueTag(pp, "id");
							XmlPullUtil.optValueTag(pp, "a", null);
							final Position position = super.parsePosition(XmlPullUtil.optValueTag(pp, "pl", null));
							final String place = normalizeLocationName(XmlPullUtil.optValueTag(pp, "pc", null));
							final Point coord = parseCoord(XmlPullUtil.optValueTag(pp, "c", null));
							XmlPullUtil.skipExit(pp, "r");

							final Location location;
							if (id.equals("99999997") || id.equals("99999998"))
								location = new Location(LocationType.ADDRESS, null, coord, place, name);
							else
								location = new Location(LocationType.STATION, id, coord, place, name);

							XmlPullUtil.skipExit(pp, "p");

							if ("departure".equals(usage))
							{
								departure = new Stop(location, true, plannedTime.isSet(Calendar.HOUR_OF_DAY) ? plannedTime.getTime()
										: predictedTime.getTime(), predictedTime.isSet(Calendar.HOUR_OF_DAY) ? predictedTime.getTime() : null,
										position, null);
								if (firstDepartureLocation == null)
									firstDepartureLocation = location;
							}
							else if ("arrival".equals(usage))
							{
								arrival = new Stop(location, false, plannedTime.isSet(Calendar.HOUR_OF_DAY) ? plannedTime.getTime()
										: predictedTime.getTime(), predictedTime.isSet(Calendar.HOUR_OF_DAY) ? predictedTime.getTime() : null,
										position, null);
								lastArrivalLocation = location;
							}
							else
							{
								throw new IllegalStateException("unknown usage: " + usage);
							}
						}

						XmlPullUtil.skipExit(pp, "ps");

						final boolean isRealtime = XmlPullUtil.valueTag(pp, "realtime").equals("1");

						final LineDestination lineDestination = parseMobileM(pp, false);

						final List<Point> path;
						if (XmlPullUtil.test(pp, "pt"))
							path = processCoordinateStrings(pp, "pt");
						else
							path = null;

						XmlPullUtil.require(pp, "pss");

						final List<Stop> intermediateStops;

						if (!pp.isEmptyElementTag())
						{
							XmlPullUtil.enter(pp, "pss");

							intermediateStops = new LinkedList<Stop>();

							while (XmlPullUtil.test(pp, "s"))
							{
								plannedTime.clear();
								predictedTime.clear();

								final String s = XmlPullUtil.valueTag(pp, "s");
								final String[] intermediateParts = s.split(";");
								final String id = intermediateParts[0];
								if (!id.equals(departure.location.id) && !id.equals(arrival.location.id))
								{
									final String name = normalizeLocationName(intermediateParts[1]);

									if (!("0000-1".equals(intermediateParts[2]) && "000-1".equals(intermediateParts[3])))
									{
										ParserUtils.parseIsoDate(plannedTime, intermediateParts[2]);
										ParserUtils.parseIsoTime(plannedTime, intermediateParts[3]);

										if (isRealtime)
										{
											ParserUtils.parseIsoDate(predictedTime, intermediateParts[2]);
											ParserUtils.parseIsoTime(predictedTime, intermediateParts[3]);

											if (intermediateParts.length > 5)
											{
												final int delay = Integer.parseInt(intermediateParts[5]);
												predictedTime.add(Calendar.MINUTE, delay);
											}
										}
									}
									final String coord = intermediateParts[4];

									final Location location;
									if (!"::".equals(coord))
									{
										final String[] coordParts = coord.split(":");
										if (!"WGS84".equals(coordParts[2]))
											throw new IllegalStateException("unknown map name: " + coordParts[2]);
										final double lat = Double.parseDouble(coordParts[1]);
										final double lon = Double.parseDouble(coordParts[0]);
										location = new Location(LocationType.STATION, id, Point.fromDouble(lat, lon), null, name);
									}
									else
									{
										location = new Location(LocationType.STATION, id, null, name);
									}

									final Stop stop = new Stop(location, false, plannedTime.isSet(Calendar.HOUR_OF_DAY) ? plannedTime.getTime()
											: predictedTime.getTime(), predictedTime.isSet(Calendar.HOUR_OF_DAY) ? predictedTime.getTime() : null,
											null, null);

									intermediateStops.add(stop);
								}
							}

							XmlPullUtil.skipExit(pp, "pss");
						}
						else
						{
							intermediateStops = null;

							XmlPullUtil.next(pp);
						}

						XmlPullUtil.optSkip(pp, "interchange");

						XmlPullUtil.requireSkip(pp, "ns");
						// TODO messages

						XmlPullUtil.skipExit(pp, "l");

						if (lineDestination.line == Line.FOOTWAY)
						{
							legs.add(new Trip.Individual(Trip.Individual.Type.WALK, departure.location, departure.getDepartureTime(),
									arrival.location, arrival.getArrivalTime(), path, 0));
						}
						else if (lineDestination.line == Line.TRANSFER)
						{
							legs.add(new Trip.Individual(Trip.Individual.Type.TRANSFER, departure.location, departure.getDepartureTime(),
									arrival.location, arrival.getArrivalTime(), path, 0));
						}
						else if (lineDestination.line == Line.SECURE_CONNECTION || lineDestination.line == Line.DO_NOT_CHANGE)
						{
							// ignore
						}
						else
						{
							legs.add(new Trip.Public(lineDestination.line, lineDestination.destination, departure, arrival, intermediateStops, path,
									null));
						}
					}

					XmlPullUtil.skipExit(pp, "ls");

					XmlPullUtil.optSkip(pp, "seqroutes");

					final List<Fare> fares;
					if (XmlPullUtil.test(pp, "tcs"))
					{
						if (!pp.isEmptyElementTag())
						{
							XmlPullUtil.enter(pp, "tcs");

							fares = new ArrayList<Fare>(2);

							while (XmlPullUtil.test(pp, "tc"))
							{
								XmlPullUtil.enter(pp, "tc");
								// TODO fares
								XmlPullUtil.skipExit(pp, "tc");
							}

							XmlPullUtil.skipExit(pp, "tcs");
						}
						else
						{
							fares = null;

							XmlPullUtil.next(pp);
						}
					}
					else
					{
						fares = null;
					}

					final Trip trip = new Trip(tripId, firstDepartureLocation, lastArrivalLocation, legs, fares, null, numChanges);
					trips.add(trip);

					XmlPullUtil.skipExit(pp, "tp");
				}

				XmlPullUtil.skipExit(pp, "ts");
			}
			else
			{
				XmlPullUtil.next(pp);
			}
		}

		if (trips.size() > 0)
		{
			final String[] context = (String[]) header.context;
			return new QueryTripsResult(header, uri, from, via, to, new Context(commandLink(context[0], context[1])), trips);
		}
		else
		{
			return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
		}
	}

	private List<Point> processItdPathCoordinates(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		XmlPullUtil.enter(pp, "itdPathCoordinates");

		final String ellipsoid = XmlPullUtil.valueTag(pp, "coordEllipsoid");
		if (!"WGS84".equals(ellipsoid))
			throw new IllegalStateException("unknown ellipsoid: " + ellipsoid);

		final String type = XmlPullUtil.valueTag(pp, "coordType");
		if (!"GEO_DECIMAL".equals(type))
			throw new IllegalStateException("unknown type: " + type);

		final List<Point> path;
		if (XmlPullUtil.test(pp, "itdCoordinateString"))
		{
			path = processCoordinateStrings(pp, "itdCoordinateString");
		}
		else if (XmlPullUtil.test(pp, "itdCoordinateBaseElemList"))
		{
			path = processCoordinateBaseElems(pp);
		}
		else
		{
			throw new IllegalStateException(pp.getPositionDescription());
		}

		XmlPullUtil.skipExit(pp, "itdPathCoordinates");

		return path;
	}

	private List<Point> processCoordinateStrings(final XmlPullParser pp, final String tag) throws XmlPullParserException, IOException
	{
		final List<Point> path = new LinkedList<Point>();

		final String value = XmlPullUtil.valueTag(pp, tag);
		for (final String coordStr : value.split(" +"))
			path.add(parseCoord(coordStr));

		return path;
	}

	private List<Point> processCoordinateBaseElems(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		final List<Point> path = new LinkedList<Point>();

		XmlPullUtil.enter(pp, "itdCoordinateBaseElemList");

		while (XmlPullUtil.test(pp, "itdCoordinateBaseElem"))
		{
			XmlPullUtil.enter(pp, "itdCoordinateBaseElem");

			final double lon = Double.parseDouble(XmlPullUtil.valueTag(pp, "x"));
			final double lat = Double.parseDouble(XmlPullUtil.valueTag(pp, "y"));
			path.add(Point.fromDouble(lat, lon));

			XmlPullUtil.skipExit(pp, "itdCoordinateBaseElem");
		}

		XmlPullUtil.skipExit(pp, "itdCoordinateBaseElemList");

		return path;
	}

	private Point parseCoord(final String coordStr)
	{
		if (coordStr == null)
			return null;

		final String[] parts = coordStr.split(",");
		final int lat = (int) Math.round(Double.parseDouble(parts[1]));
		final int lon = (int) Math.round(Double.parseDouble(parts[0]));
		return new Point(lat, lon);
	}

	private Point processCoordAttr(final XmlPullParser pp)
	{
		final String mapName = XmlPullUtil.optAttr(pp, "mapName", null);
		final int x = (int) Math.round(XmlPullUtil.optFloatAttr(pp, "x", 0));
		final int y = (int) Math.round(XmlPullUtil.optFloatAttr(pp, "y", 0));

		if (mapName == null || (x == 0 && y == 0))
			return null;

		if (!"WGS84".equals(mapName))
			throw new IllegalStateException("unknown mapName=" + mapName);

		return new Point(y, x);
	}

	private Fare processItdGenericTicketGroup(final XmlPullParser pp, final String net, final Currency currency) throws XmlPullParserException,
			IOException
	{
		XmlPullUtil.enter(pp, "itdGenericTicketGroup");

		Type type = null;
		float fare = 0;

		while (XmlPullUtil.test(pp, "itdGenericTicket"))
		{
			XmlPullUtil.enter(pp, "itdGenericTicket");

			final String key = XmlPullUtil.valueTag(pp, "ticket");
			final String value = XmlPullUtil.valueTag(pp, "value");

			if (key.equals("FOR_RIDER"))
			{
				final String typeStr = value.split(" ")[0].toUpperCase();
				if (typeStr.equals("REGULAR"))
					type = Type.ADULT;
				else
					type = Type.valueOf(typeStr);
			}
			else if (key.equals("PRICE"))
			{
				fare = Float.parseFloat(value) * fareCorrectionFactor;
			}

			XmlPullUtil.skipExit(pp, "itdGenericTicket");
		}

		XmlPullUtil.skipExit(pp, "itdGenericTicketGroup");

		if (type != null)
			return new Fare(net, type, currency, fare, null, null);
		else
			return null;
	}

	private Currency parseCurrency(final String currencyStr)
	{
		if (currencyStr.equals("US$"))
			return Currency.getInstance("USD");
		if (currencyStr.equals("Dirham"))
			return Currency.getInstance("AED");
		return Currency.getInstance(currencyStr);
	}

	private static final Pattern P_POSITION = Pattern.compile(
			"(?:Gleis|Gl\\.|Bahnsteig|Bstg\\.|Bussteig|Busstg\\.|Steig|Hp\\.|Stop|Pos\\.|Zone|Platform)?\\s*(.+)", Pattern.CASE_INSENSITIVE);

	@Override
	protected Position parsePosition(final String position)
	{
		if (position == null)
			return null;

		if (position.startsWith("Ri.") || position.startsWith("Richtung "))
			return null;

		final Matcher m = P_POSITION.matcher(position);
		if (m.matches())
			return super.parsePosition(m.group(1));

		return super.parsePosition(position);
	}

	private void appendLocation(final StringBuilder uri, final Location location, final String paramSuffix)
	{
		if (location.type == LocationType.ADDRESS && location.hasLocation())
		{
			uri.append("&type_").append(paramSuffix).append("=coord");
			uri.append("&name_").append(paramSuffix).append("=")
					.append(String.format(Locale.ENGLISH, "%.6f:%.6f", location.lon / 1E6, location.lat / 1E6)).append(":WGS84");
		}
		else
		{
			uri.append("&type_").append(paramSuffix).append("=").append(locationTypeValue(location));
			uri.append("&name_").append(paramSuffix).append("=").append(ParserUtils.urlEncode(locationValue(location), requestUrlEncoding));
		}
	}

	protected static final String locationTypeValue(final Location location)
	{
		final LocationType type = location.type;
		if (type == LocationType.STATION)
			return "stop";
		if (type == LocationType.ADDRESS)
			return "any"; // strange, matches with anyObjFilter
		if (type == LocationType.POI)
			return "poi";
		if (type == LocationType.ANY)
			return "any";
		throw new IllegalArgumentException(type.toString());
	}

	protected static final String locationValue(final Location location)
	{
		if (location.type == LocationType.STATION && location.hasId())
			return normalizeStationId(location.id);
		else if (location.type == LocationType.POI && location.hasId())
			return location.id;
		else
			return location.name;
	}

	protected static final Map<WalkSpeed, String> WALKSPEED_MAP = new HashMap<WalkSpeed, String>();

	static
	{
		WALKSPEED_MAP.put(WalkSpeed.SLOW, "slow");
		WALKSPEED_MAP.put(WalkSpeed.NORMAL, "normal");
		WALKSPEED_MAP.put(WalkSpeed.FAST, "fast");
	}

	private ResultHeader enterItdRequest(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		if (pp.getEventType() != XmlPullParser.START_DOCUMENT)
			throw new ParserException("start of document expected");

		pp.next();

		if (pp.getEventType() == XmlPullParser.DOCDECL)
			pp.next();

		if (pp.getEventType() == XmlPullParser.END_DOCUMENT)
			throw new ParserException("empty document");

		XmlPullUtil.require(pp, "itdRequest");

		final String serverVersion = XmlPullUtil.attr(pp, "version");
		final String now = XmlPullUtil.attr(pp, "now");
		final String sessionId = XmlPullUtil.attr(pp, "sessionID");

		final Calendar serverTime = new GregorianCalendar(timeZone);
		ParserUtils.parseIsoDate(serverTime, now.substring(0, 10));
		ParserUtils.parseEuropeanTime(serverTime, now.substring(11));

		final ResultHeader header = new ResultHeader(SERVER_PRODUCT, serverVersion, serverTime.getTimeInMillis(), sessionId);

		XmlPullUtil.enter(pp, "itdRequest");

		if (XmlPullUtil.test(pp, "clientHeaderLines"))
			XmlPullUtil.next(pp);

		if (XmlPullUtil.test(pp, "itdMessageList"))
			XmlPullUtil.next(pp);

		if (XmlPullUtil.test(pp, "itdVersionInfo"))
			XmlPullUtil.next(pp);

		if (XmlPullUtil.test(pp, "itdInfoLinkList"))
			XmlPullUtil.next(pp);

		if (XmlPullUtil.test(pp, "serverMetaInfo"))
			XmlPullUtil.next(pp);

		return header;
	}

	private ResultHeader enterEfa(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		if (pp.getEventType() != XmlPullParser.START_DOCUMENT)
			throw new ParserException("start of document expected");

		pp.next();

		if (pp.getEventType() == XmlPullParser.END_DOCUMENT)
			throw new ParserException("empty document");

		XmlPullUtil.enter(pp, "efa");

		final String now = XmlPullUtil.valueTag(pp, "now");
		final Calendar serverTime = new GregorianCalendar(timeZone);
		ParserUtils.parseIsoDate(serverTime, now.substring(0, 10));
		ParserUtils.parseEuropeanTime(serverTime, now.substring(11));

		final Map<String, String> params = processPas(pp);
		final String sessionId = params.get("sessionID");
		final String requestId = params.get("requestID");

		final ResultHeader header = new ResultHeader(SERVER_PRODUCT, null, serverTime.getTimeInMillis(), new String[] { sessionId, requestId });

		return header;
	}

	private Map<String, String> processPas(final XmlPullParser pp) throws XmlPullParserException, IOException
	{
		final Map<String, String> params = new HashMap<String, String>();

		XmlPullUtil.enter(pp, "pas");

		while (XmlPullUtil.test(pp, "pa"))
		{
			XmlPullUtil.enter(pp, "pa");
			final String name = XmlPullUtil.valueTag(pp, "n");
			final String value = XmlPullUtil.valueTag(pp, "v");
			params.put(name, value);
			XmlPullUtil.skipExit(pp, "pa");
		}

		XmlPullUtil.skipExit(pp, "pas");

		return params;
	}
}
