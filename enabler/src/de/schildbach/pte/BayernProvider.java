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
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;

import com.google.common.base.Charsets;

import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.SuggestLocationsResult;

/**
 * @author Andreas Schildbach
 */
public class BayernProvider extends AbstractEfaProvider
{
	public static final NetworkId NETWORK_ID = NetworkId.BAYERN;
	private final static String API_BASE = "http://mobile.defas-fgi.de/beg/";

	// http://mobile.defas-fgi.de/xml/

	private static final String DEPARTURE_MONITOR_ENDPOINT = "XML_DM_REQUEST";
	private static final String TRIP_ENDPOINT = "XML_TRIP_REQUEST2";
	private static final String STOP_FINDER_ENDPOINT = "XML_STOPFINDER_REQUEST";

	public BayernProvider()
	{
		super(API_BASE, DEPARTURE_MONITOR_ENDPOINT, TRIP_ENDPOINT, STOP_FINDER_ENDPOINT, null);

		setRequestUrlEncoding(Charsets.UTF_8);
		setIncludeRegionId(false);
		setNumTripsRequested(12);
	}

	public NetworkId id()
	{
		return NETWORK_ID;
	}

	@Override
	protected String parseLine(final String mot, final String symbol, final String name, final String longName, final String trainType,
			final String trainNum, final String trainName)
	{
		if ("0".equals(mot))
		{
			if ("M".equals(trainType) && trainNum != null && trainName != null && trainName.endsWith("Meridian"))
				return "RM" + trainNum;
			if ("ZUG".equals(trainType) && trainNum != null)
				return "R" + trainNum;
		}
		else if ("16".equals(mot))
		{
			if ("EC".equals(trainType))
				return "IEC" + trainNum;
			if ("IC".equals(trainType))
				return "IIC" + trainNum;
			if ("ICE".equals(trainType))
				return "IICE" + trainNum;
			if ("CNL".equals(trainType))
				return "ICNL" + trainNum;
			if ("THA".equals(trainType)) // Thalys
				return "ITHA" + trainNum;
			if ("TGV".equals(trainType)) // Train a grande Vitesse
				return "ITGV" + trainNum;
			if ("RJ".equals(trainType)) // railjet
				return "IRJ" + trainNum;
			if ("WB".equals(trainType)) // WESTbahn
				return "IWB" + trainNum;
			if ("HKX".equals(trainType)) // Hamburg-Köln-Express
				return "IHKX" + trainNum;
			if ("D".equals(trainType)) // Schnellzug
				return "ID" + trainNum;

			if ("IR".equals(trainType)) // InterRegio
				return "RIR" + trainNum;
		}

		return super.parseLine(mot, symbol, name, longName, trainType, trainNum, trainName);
	}

	@Override
	public NearbyLocationsResult queryNearbyLocations(final EnumSet<LocationType> types, final Location location, final int maxDistance,
			final int maxLocations) throws IOException
	{
		if (location.hasLocation())
			return mobileCoordRequest(types, location.lat, location.lon, maxDistance, maxLocations);

		if (location.type != LocationType.STATION)
			throw new IllegalArgumentException("cannot handle: " + location.type);

		throw new IllegalArgumentException("station"); // TODO
	}

	@Override
	public QueryDeparturesResult queryDepartures(final String stationId, final Date time, final int maxDepartures, final boolean equivs)
			throws IOException
	{
		return queryDeparturesMobile(stationId, time, maxDepartures, equivs);
	}

	@Override
	public SuggestLocationsResult suggestLocations(final CharSequence constraint) throws IOException
	{
		return mobileStopfinderRequest(new Location(LocationType.ANY, null, null, constraint.toString()));
	}

	@Override
	public QueryTripsResult queryTrips(final Location from, final Location via, final Location to, final Date date, final boolean dep,
			final Set<Product> products, final WalkSpeed walkSpeed, final Accessibility accessibility, final Set<Option> options) throws IOException
	{
		return queryTripsMobile(from, via, to, date, dep, products, walkSpeed, accessibility, options);
	}

	@Override
	public QueryTripsResult queryMoreTrips(final QueryTripsContext contextObj, final boolean later) throws IOException
	{
		return queryMoreTripsMobile(contextObj, later);
	}
}
