package de.westnordost.countryboundaries;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class CountryBoundariesGenerator
{
	private static final int WGS84 = 4326;

	private final boolean parallel;
	private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(), WGS84);
	private ProgressListener listener;

	public CountryBoundariesGenerator(boolean parallel) {
		this.parallel = parallel;
	}

	public interface ProgressListener
	{
		void onProgress(float progress);
	}

	public void setProgressListener(ProgressListener listener)
	{
		this.listener = listener;
	}

	public CountryBoundaries generate(int width, int height, List<Geometry> boundaries)
	{
		Map<String, Double> geometrySizes = calculateGeometryAreas(boundaries);

		STRtree index = buildIndex(boundaries);
		CountryBoundariesCell[] raster = parallel
				? getCountryBoundariesCellsParallel(width, height, index)
				: getCountryBoundariesCellsSequential(width, height, index);
		if (listener != null) listener.onProgress(1);

		return new CountryBoundaries(raster, width, geometrySizes);
	}

	private CountryBoundariesCell[] getCountryBoundariesCellsParallel(int width, int height, STRtree index) {
		AtomicInteger counter = new AtomicInteger(0);
		int maxValue = width * height;
		return Stream.iterate(0, y -> y + 1).limit(height)
		.flatMap(y -> Stream.iterate(0, x -> x + 1).limit(width).map(x -> new Point(x, y)))
		.parallel()
		.map(p -> {
			CountryBoundariesCell cell = getCountryBoundariesCell(width, height, index, p);
			if (listener != null) {
				int currentCounterValue = counter.incrementAndGet();
				listener.onProgress((float)(currentCounterValue)/(maxValue));
			}
			return cell;
		})
		.toArray(CountryBoundariesCell[]::new);
	}

	private CountryBoundariesCell[] getCountryBoundariesCellsSequential(int width, int height, STRtree index) {
		CountryBoundariesCell[] raster = new CountryBoundariesCell[width * height];

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				double lonMin = -180.0 + 360.0 * x / width;
				double latMax = +90.0 - 180.0 * y / height;
				double lonMax = -180.0 + 360.0 * (x + 1) / width;
				double latMin = +90.0 - 180.0 * (y + 1) / height;

				raster[x + y * width] = createCell(index, lonMin, latMin, lonMax, latMax);

				if (listener != null) listener.onProgress((float)(y*width+x)/(width*height));
			}
		}
		return raster;
	}

	private CountryBoundariesCell getCountryBoundariesCell(int width, int height, STRtree index, Point p) {
		double lonMin = -180.0 + 360.0 * p.x / width;
		double latMax = +90.0 - 180.0 * p.y / height;
		double lonMax = -180.0 + 360.0 * (p.x + 1) / width;
		double latMin = +90.0 - 180.0 * (p.y + 1) / height;
		return createCell(index, lonMin, latMin, lonMax, latMax);
	}

	private CountryBoundariesCell createCell(
			STRtree index, double lonMin, double latMin, double lonMax, double latMax)
	{
		Polygon bounds = createBounds(lonMin, latMin, lonMax, latMax);

		List<String> containingIds = new ArrayList<>();
		List<CountryAreas> intersectingAreas = new ArrayList<>();

		List<Geometry> geometries = index.query(bounds.getEnvelopeInternal());
		for (Geometry g : geometries)
		{
			String areaId = getAreaId(g);
			if(areaId == null) continue;

			IntersectionMatrix im = g.relate(bounds);
			if(im.isCovers())
			{
				containingIds.add(areaId);
			}
			else if(!im.isDisjoint())
			{
				Geometry intersection = g.intersection(bounds);
				if(!(intersection instanceof Polygonal))
				{
					continue;
				}
				intersection.normalize();
				intersectingAreas.add(createCountryAreas(areaId, intersection));
			}
		}
		return new CountryBoundariesCell(containingIds, intersectingAreas);
	}

	private CountryAreas createCountryAreas(String areaId, Geometry intersection)
	{
		List<Point[]> outer = new ArrayList<>(), inner = new ArrayList<>();

		if(intersection instanceof Polygon)
		{
			Polygon p = (Polygon) intersection;
			outer.add(createPoints(p.getExteriorRing()));
			for (int j = 0; j < p.getNumInteriorRing(); j++)
			{
				inner.add(createPoints(p.getInteriorRingN(j)));
			}
		}
		else
		{
			MultiPolygon mp = (MultiPolygon) intersection;
			for (int i = 0; i < mp.getNumGeometries(); i++)
			{
				Polygon p = (Polygon) mp.getGeometryN(i);
				outer.add(createPoints(p.getExteriorRing()));
				for (int j = 0; j < p.getNumInteriorRing(); j++)
				{
					inner.add(createPoints(p.getInteriorRingN(j)));
				}
			}
		}
		return new CountryAreas(areaId,outer.toArray(new Point[][]{}), inner.toArray(new Point[][]{}));
	}

	private Point[] createPoints(LineString ring)
	{
		Coordinate[] coords = ring.getCoordinates();
		// leave out last - not necessary
		Point[] result = new Point[coords.length-1];
		for (int i = 0; i < coords.length-1; i++)
		{
			Coordinate coord = coords[i];
			result[i] = new Point(Fixed1E7.doubleToFixed(coord.x), Fixed1E7.doubleToFixed(coord.y));
		}
		return result;
	}

	private STRtree buildIndex(List<Geometry> geometries)
	{
		STRtree index = new STRtree();
		for (Geometry g : geometries)
		{
			String areaId = getAreaId(g);
			if(areaId != null)
			{
				index.insert(g.getEnvelopeInternal(), g);
			}
		}
		return index;
	}

	private Map<String,Double> calculateGeometryAreas(List<Geometry> geometries)
	{
		Map<String, Double> geometryAreas = new HashMap<>(geometries.size());
		for (Geometry g : geometries)
		{
			String areaId = getAreaId(g);
			if(areaId != null)
			{
				geometryAreas.put(areaId, g.getArea());
			}
		}
		return geometryAreas;
	}

	private String getAreaId(Geometry g)
	{
		if(g instanceof Polygonal)
		{
			Object data = g.getUserData();
			if(data != null && data instanceof String)
			{
				return (String) data;
			}
		}
		return null;
	}

	private Polygon createBounds(double lonMin, double latMin, double lonMax, double latMax)
	{
		return factory.createPolygon(new Coordinate[]
		{
			new Coordinate(lonMin, latMin),
			new Coordinate(lonMin, latMax),
			new Coordinate(lonMax, latMax),
			new Coordinate(lonMax, latMin),
			new Coordinate(lonMin, latMin)
		});
	}
}
