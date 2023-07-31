package paulevs.merger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MappingsMerger {
	public static void main(String[] args) {
		if (args.length != 3) {
			System.out.println("Usage: merger.jar <source_1> <source_2> <output>");
			return;
		}
		
		List<File> source1 = getFiles(new File(args[0]), new ArrayList<>());
		List<File> source2 = getFiles(new File(args[1]), new ArrayList<>());
		File output = new File(args[2]);
		
		Map<String, ClassMapping> mappings = new HashMap<>();
		
		source1.stream().map(MappingsMerger::readMappingFile).forEach(mapping -> mappings.put(mapping.className, mapping));
		source2.stream().map(MappingsMerger::readMappingFile).forEach(mapping2 -> {
			ClassMapping mapping1 = mappings.get(mapping2.className);
			if (mapping1 != null) {
				mapping2 = mergeClasses(mapping1, mapping2);
				System.out.println("Merged: " + mapping1.className);
			}
			mappings.put(mapping2.className, mapping2);
		});
		
		mappings.values().forEach(mapping -> {
			File out = new File(output, mapping.getFileName());
			writeMappingFile(out, mapping);
		});
	}
	
	private static List<File> getFiles(File folder, List<File> out) {
		File[] files = folder.listFiles();
		if (files == null) return out;
		Arrays.stream(files).forEach(file -> {
			if (file.isDirectory()) getFiles(file, out);
			else if (file.getName().endsWith(".mapping")) out.add(file);
		});
		return out;
	}
	
	private static ClassMapping readMappingFile(File file) {
		List<String> lines = null;
		try {
			lines = Files.readAllLines(file.toPath());
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		return getMapping(lines, new AtomicInteger());
	}
	
	private static void writeMappingFile(File file, ClassMapping mapping) {
		try {
			Files.createDirectories(file.getParentFile().toPath());
			Files.writeString(file.toPath(), mapping.asString(0), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static ClassMapping getMapping(List<String> lines, AtomicInteger index) {
		String line = lines.get(index.getAndIncrement());
		int startingTabs = getTabs(line);
		String[] parts = line.trim().split(" ");
		
		String className = parts.length > 2 ? parts[2] : parts[1];
		ClassMapping mapping = new ClassMapping(parts[1], className);
		MethodMapping activeMethod = null;
		
		while (index.get() < lines.size()) {
			line = lines.get(index.get());
			
			int tabs = getTabs(line);
			if (tabs <= startingTabs) return mapping;
			
			line = line.trim();
			parts = line.split(" ");
			
			switch (parts[0]) {
				case "CLASS" -> mapping.children.add(getMapping(lines, index));
				case "FIELD" -> mapping.fieldMappings.put(parts[1], line);
				case "METHOD" -> {
					activeMethod = new MethodMapping(parts[1], line);
					String name = parts[1].startsWith("method_") ? parts[1] : parts[1] + " " + line;
					mapping.methodsMappings.put(name, activeMethod);
				}
				case "ARG" -> {
					if (activeMethod != null) {
						activeMethod.args.put(Integer.parseInt(parts[1]), line);
					}
				}
			}
			
			index.incrementAndGet();
		}
		
		return mapping;
	}
	
	private static int getTabs(String line) {
		int count = 0;
		while (count < line.length() && line.charAt(count) == '\t') count++;
		return count;
	}
	
	private static ClassMapping mergeClasses(ClassMapping a, ClassMapping b) {
		String className = a.classMapping.equals(a.className) ? b.classMapping : a.classMapping;
		ClassMapping result = new ClassMapping(a.className, className);
		
		mergeSets(a.children, b.children, result.children);
		mergeMaps(a.fieldMappings, b.fieldMappings, result.fieldMappings);
		
		a.methodsMappings.forEach((name, mapping1) -> {
			MethodMapping mapping2 = b.methodsMappings.get(name);
			if (mapping2 != null) {
				MethodMapping resultMapping = new MethodMapping(mapping1.methodName, mapping1.methodString);
				mergeMaps(mapping1.args, mapping2.args, resultMapping.args);
				result.methodsMappings.put(name, resultMapping);
			}
		});
		mergeMaps(a.methodsMappings, b.methodsMappings, result.methodsMappings);
		
		return result;
	}
	
	private static <K, V> void mergeMaps(Map<K, V> a, Map<K, V> b, Map<K, V> out) {
		a.forEach((k, v) -> { if (!out.containsKey(k)) out.put(k, v); });
		b.forEach((k, v) -> { if (!out.containsKey(k)) out.put(k, v); });
	}
	
	private static <V> void mergeSets(Set<V> a, Set<V> b, Set<V> out) {
		out.addAll(a);
		out.addAll(b);
	}
	
	private static void tabs(StringBuilder builder, int count) {
		for (int i = 0; i < count; i++) builder.append('\t');
	}
	
	private static class ClassMapping {
		final String className;
		final String classMapping;
		
		final Map<String, String> fieldMappings = new HashMap<>();
		final Map<String, MethodMapping> methodsMappings = new HashMap<>();
		
		final Set<ClassMapping> children = new HashSet<>();
		
		ClassMapping(String className, String classMapping) {
			this.className = className;
			this.classMapping = classMapping;
		}
		
		String getFileName() {
			return classMapping.replace("\\.", "/") + ".mapping";
		}
		
		String asString(int tabs) {
			StringBuilder builder = new StringBuilder();
			tabs(builder, tabs);
			builder.append("CLASS ");
			builder.append(className);
			if (!classMapping.substring(classMapping.lastIndexOf('/') + 1).startsWith("class_")) {
				builder.append(' ');
				builder.append(classMapping);
			}
			builder.append('\n');
			
			final int innerTabs = tabs + 1;
			
			fieldMappings.values().stream().sorted().forEach(field -> {
				tabs(builder, innerTabs);
				builder.append(field);
				builder.append('\n');
			});
			
			methodsMappings
				.values()
				.stream()
				.sorted(Comparator.comparing(m -> m.methodName))
				.forEach(m -> builder.append(m.asString(innerTabs)));
			
			children.stream().sorted(Comparator.comparing(c -> c.className)).forEach(child -> {
				builder.append(child.asString(innerTabs));
				builder.append('\n');
			});
			
			return builder.toString();
		}
	}
	
	private static class MethodMapping {
		final String methodName;
		final String methodString;
		final Map<Integer, String> args = new HashMap<>();
		
		MethodMapping(String methodName, String methodString) {
			this.methodName = methodName;
			this.methodString = methodString;
		}
		
		String asString(int tabs) {
			StringBuilder builder = new StringBuilder();
			tabs(builder, tabs);
			builder.append(methodString);
			builder.append('\n');
			args.keySet().stream().sorted().forEach(key -> {
				tabs(builder, tabs + 1);
				builder.append(args.get(key));
				builder.append('\n');
			});
			return builder.toString();
		}
	}
}
