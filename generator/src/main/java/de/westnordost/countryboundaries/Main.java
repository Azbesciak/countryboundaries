package de.westnordost.countryboundaries;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Main
{
	public static void main(String[] args) throws Exception {

		if(args.length < 3) {
			System.err.println("Missing parameters. F.e. 'boundaries.osm 360 180' ");
			return;
		}

		String filename = args[0];
		int width = Integer.parseInt(args[1]);
		int height = Integer.parseInt(args[2]);
		boolean parallel = false;
		if (args.length > 3) {
			String candidate = args[3];
			parallel = candidate.equals("-p") || candidate.equals("--parallel");
		}

		FileInputStream is = new FileInputStream(filename);

		GeometryCollection geometries;
		if(filename.endsWith(".json") || filename.endsWith(".geojson"))
		{
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int length;
			while ((length = is.read(buffer)) != -1)
			{
				baos.write(buffer, 0, length);
			}
			geometries = (GeometryCollection) new GeoJsonReader().read(baos.toString("UTF-8"));
		}
		else if(filename.endsWith(".osm"))
		{
			geometries = new JosmCountryBoundariesReader().read(new InputStreamReader(is, "UTF-8"));
		}
		else
		{
			System.err.println("Input file must be a OSM XML (.osm) or a GeoJSON (.json/.geojson)");
			return;
		}

		//String geojson = new GeoJsonWriter().write(geometries);
		//try(Writer writer = new OutputStreamWriter(new FileOutputStream("boundaries.json"), "UTF-8"))
		//{
		//	writer.write(geojson);
		//}

		Set<String> excludeCountries = new HashSet<>();
		excludeCountries.add("FX");
		excludeCountries.add("EU");

		List<Geometry> geometryList = new ArrayList<>(geometries.getNumGeometries());
		for (int i = 0; i < geometries.getNumGeometries(); i++)
		{
			Geometry g = geometries.getGeometryN(i);
			Object id = ((Map)g.getUserData()).get("id");
			if (id instanceof String && !excludeCountries.contains(id)) {
				g.setUserData(id);
				geometryList.add(g);
			}
		}

		System.out.println("Generating index...");

		CountryBoundariesGenerator generator = new CountryBoundariesGenerator(parallel);
		generator.setProgressListener(new CountryBoundariesGenerator.ProgressListener()
		{
			volatile int currentProgress = 0;

			@Override public void onProgress(float progress)
			{
				int newProgress = (int) (progress * 1000);
				if (currentProgress != newProgress) {
					currentProgress = newProgress;
					String percentDone = "Progress: " + String.format(Locale.US, "%.1f", 100 * progress) + "%\r";
					System.out.print(percentDone);
				}
			}
		});

		CountryBoundaries boundaries = generator.generate(width, height, geometryList);
		try(FileOutputStream fos = new FileOutputStream("boundaries.ser"))
		{
			try(ObjectOutputStream oos = new ObjectOutputStream(fos))
			{
				boundaries.write(oos);
			}
		}
	}
}
