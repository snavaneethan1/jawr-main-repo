/**
 * Copyright 2011-2015 Ibrahim Chaehoi
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
package net.jawr.web.resource.bundle.global.postprocessor.google.closure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import net.jawr.web.JawrConstant;
import net.jawr.web.config.JawrConfig;
import net.jawr.web.exception.BundlingProcessException;
import net.jawr.web.exception.ResourceNotFoundException;
import net.jawr.web.resource.bundle.IOUtils;
import net.jawr.web.resource.bundle.JoinableResourceBundle;
import net.jawr.web.resource.bundle.factory.global.postprocessor.GlobalPostProcessingContext;
import net.jawr.web.resource.bundle.factory.util.PathNormalizer;
import net.jawr.web.resource.bundle.global.processor.AbstractChainedGlobalProcessor;
import net.jawr.web.resource.bundle.handler.ResourceBundlesHandler;
import net.jawr.web.resource.bundle.variant.VariantSet;
import net.jawr.web.resource.bundle.variant.VariantUtils;
import net.jawr.web.util.FileUtils;
import net.jawr.web.util.StringUtils;
import net.jawr.web.util.io.TeeOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.CharStreams;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.SourceFile;

/**
 * This class defines the Closure global post processor. This post processor
 * will work on the bundle generated by the Jawr processing. By default, this
 * postprocessor will use the WHITESPACE_ONLY compilation level.
 * 
 * @author Ibrahim Chaehoi
 */
public class ClosureGlobalPostProcessor extends
		AbstractChainedGlobalProcessor<GlobalPostProcessingContext> {

	/** The logger */
	private static final Logger LOGGER = LoggerFactory
			.getLogger(ClosureGlobalPostProcessor.class);

	/** The list of unallowed compiler argument in the Jawr config */
	private List<String> UNALLOWED_COMPILER_ARGS = Arrays
			.asList("js", "module");

	/** The closure compiler argument name prefix */
	private static final String CLOSURE_ARGUMENT_NAME_PREFIX = "--";

	/** The Jawr closure argument prefix in the Jawr configuration */
	private static final String JAWR_JS_CLOSURE_PREFIX = "jawr.js.closure.";

	/** The module argument for the closure command line runner */
	private static final String MODULE_ARG = "--module";

	/** The module argument pattern */
	private static final Pattern MODULE_ARG_PATTERN = Pattern.compile("[^:]+:\\d+:(.*)");
	
	/** The module dependencies separator */
	private static final String MODULE_DEPENDENCIES_SEPARATOR = ",";

	/** The js argument for the closure command line runner */
	private static final String JS_ARG = "--js";

	/** The compilation level argument for the closure command line runner */
	private static final String COMPILATION_LEVEL = "compilation_level";

	/** The compilation level argument for the closure command line runner */
	private static final String COMPILATION_LEVEL_ARG = CLOSURE_ARGUMENT_NAME_PREFIX
			+ COMPILATION_LEVEL;

	/** The whitespace_only compilation level */
	private static final String WHITESPACE_ONLY_COMPILATION_LEVEL = "WHITESPACE_ONLY";

	/** The simple optimization compilation level */
	private static final String SIMPLE_OPTIMIZATIONS_COMPILATION_LEVEL = "SIMPLE_OPTIMIZATIONS";

	/** The advanced optimizations compilation level */
	private static final String ADVANCED_OPTIMIZATIONS_COMPILATION_LEVEL = "ADVANCED_OPTIMIZATIONS";

	/** The warning level argument for the closure command line runner */
	private static final String WARNING_LEVEL_ARG = "--warning_level";

	/** The verbose warning level for the closure compiler */
	private static final String VERBOSE_WARNING_LEVEL = "VERBOSE";

	/** The property for the excluded bundles */
	private static final String JAWR_JS_CLOSURE_BUNDLES_EXCLUDED = "jawr.js.closure.bundles.excluded";

	/** The property for disable thread */
	private static final String JAWR_JS_CLOSURE_DISABLE_THREAD = "jawr.js.closure.disableThread";

	/**
	 * The closure modules property, whose the value will be passed to the
	 * closure compiler
	 */
	private static final String JAWR_JS_CLOSURE_MODULES = "jawr.js.closure.modules";

	/** The list of the jawr js closure properties */
	private static final List<String> JAWR_JS_CLOSURE_SPECIFIC_PROPERTIES = Arrays
			.asList(JAWR_JS_CLOSURE_BUNDLES_EXCLUDED,
					JAWR_JS_CLOSURE_DISABLE_THREAD, JAWR_JS_CLOSURE_MODULES);

	/** The google closure temporary directory */
	public static final String GOOGLE_CLOSURE_TEMP_DIR = "/googleClosure/temp/";

	/** The google closure result directory */
	public static final String GOOGLE_CLOSURE_RESULT_TEXT_DIR = "/googleClosure/text/";

	/** The google closure result zipped directory */
	public static final String GOOGLE_CLOSURE_RESULT_ZIP_DIR = "/googleClosure/gzip/";

	/** The JAWR root module file path */
	private static final String JAWR_ROOT_MODULE_JS = "/JAWR_ROOT_MODULE.js";

	/** The JAWR root module file name */
	private static final String JAWR_ROOT_MODULE_NAME = "JAWR_ROOT_MODULE";

	/** The source directory */
	private String srcDir;

	/** The source directory of zipped bundle */
	private String srcZipDir;

	/** The destination directory for closure compiler compilation */
	private String destDir;

	/** The destination directory containing the zipped compiled bundle */
	private String destZipDir;

	/** The temporary directory */
	private String tempDir;

	/**
	 * Constructor
	 */
	public ClosureGlobalPostProcessor() {
		super(JawrConstant.GLOBAL_GOOGLE_CLOSURE_POSTPROCESSOR_ID);
	}

//	/**
//	 * Constructor
//	 */
//	public ClosureGlobalPostProcessor(String srcDir, String srcZipDir, String tempDir,
//			String destDir, String destZipDir) {
//		super(JawrConstant.GLOBAL_GOOGLE_CLOSURE_POSTPROCESSOR_ID);
//		this.srcDir = srcDir;
//		this.srcZipDir = srcZipDir;
//		this.destDir = destDir;
//		this.destZipDir = destZipDir;
//		this.tempDir = tempDir;
//	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * net.jawr.web.resource.bundle.global.processor.GlobalProcessor#processBundles
	 * (
	 * net.jawr.web.resource.bundle.global.processor.AbstractGlobalProcessingContext
	 * , java.util.List)
	 */
	public void processBundles(GlobalPostProcessingContext ctx,
			List<JoinableResourceBundle> bundles) {

		if (ctx.hasBundleToBeProcessed()) {
			String workingDir = ctx.getRsReaderHandler().getWorkingDirectory();

			if (srcDir == null || destDir == null || tempDir == null
					|| srcZipDir == null || destZipDir == null) {
				srcDir = ctx.getBundleHandler().getBundleTextDirPath();
				srcZipDir = ctx.getBundleHandler().getBundleZipDirPath();
				destDir = workingDir + GOOGLE_CLOSURE_RESULT_TEXT_DIR;
				destZipDir = workingDir + GOOGLE_CLOSURE_RESULT_ZIP_DIR;
				tempDir = workingDir + GOOGLE_CLOSURE_TEMP_DIR;
			}

			// Create result directory
			File dir = new File(destDir);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new BundlingProcessException(
						"Impossible to create temporary directory :" + destDir);
			}

			// Create result directory
			dir = new File(destZipDir);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new BundlingProcessException(
						"Impossible to create temporary directory :" + destZipDir);
			}

			// Create temporary directory
			dir = new File(tempDir);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new BundlingProcessException(
						"Impossible to create temporary directory :" + tempDir);
			}

			// Copy the bundle files in a temp directory
			try {
				FileUtils.copyDirectory(new File(srcDir), new File(tempDir));

				Map<String, String> resultBundleMapping = new HashMap<String, String>();
				JawrClosureCommandLineRunner cmdRunner = new JawrClosureCommandLineRunner(
						ctx, bundles, resultBundleMapping);
				cmdRunner.doRun();
				// Copy compiled bundles
				FileUtils.copyDirectory(new File(destDir), new File(srcDir));
				
				// Copy zipped compiled bundles
				FileUtils.copyDirectory(new File(destZipDir), new File(srcZipDir));
		
			} catch (Exception e) {

				throw new BundlingProcessException(e);
			}
		}
	}

	
	/**
	 * Returns the closure compiler arguments
	 * 
	 * @param ctx
	 *            the global processing context
	 * @param tmpBundles
	 *            the bundles
	 * @param resultBundlePathMapping
	 *            the object which defines the mapping between the bundle name
	 *            and the bundle path
	 * @return
	 */
	private String[] getClosureCompilerArgs(GlobalPostProcessingContext ctx,
			List<JoinableResourceBundle> tmpBundles,
			Map<String, String> resultBundlePathMapping) {

		List<String> args = new ArrayList<String>();
		JawrConfig config = ctx.getJawrConfig();
		List<JoinableResourceBundle> bundles = new ArrayList<JoinableResourceBundle>(
				tmpBundles);

		// Handle All closure parameters defined in Jawr config
		initCompilerClosureArgumentsFromConfig(args, config);

		String excludedBundlesProp = config.getProperty(
				JAWR_JS_CLOSURE_BUNDLES_EXCLUDED, "");
		List<String> excludedBundles = Arrays.asList(excludedBundlesProp
				.replaceAll(" ", "").split(MODULE_DEPENDENCIES_SEPARATOR));

		// handle user specified modules
		Map<String, JoinableResourceBundle> bundleMap = new HashMap<String, JoinableResourceBundle>();
		for (JoinableResourceBundle bundle : bundles) {
			if (!excludedBundles.contains(bundle.getName())) {
				bundleMap.put(bundle.getName(), bundle);
			}
		}

		String modules = config.getProperty(JAWR_JS_CLOSURE_MODULES);
		List<String> depModulesArgs = new ArrayList<String>();

		List<String> globalBundleDependencies = getGlobalBundleDependencies(
				ctx, excludedBundles);

		// Initialize the modules arguments
		initModulesArgs(resultBundlePathMapping, args, bundles, bundleMap,
				modules, depModulesArgs, globalBundleDependencies);

		// handle the other bundles
		for (JoinableResourceBundle bundle : bundles) {
			if (!excludedBundles.contains(bundle.getName())) {
				generateBundleModuleArgs(args, bundleMap, resultBundlePathMapping,
						bundle, globalBundleDependencies);
			}
		}

		// Add dependency modules args after to conform to dependency definition
		// of closure args
		args.addAll(depModulesArgs);

		if (LOGGER.isDebugEnabled()) {
			StringBuilder strArg = new StringBuilder();
			for (String arg : args) {
				strArg.append(arg + " ");
			}

			LOGGER.debug("Closure Compiler Args : " + strArg.toString());
		}
		return args.toArray(new String[] {});
	}

	/**
	 * Returns the global bundle dependencies
	 * 
	 * @param ctx
	 *            the context
	 * @param excludedBundles
	 *            the excluded bundles
	 * @return the global bundle dependencies
	 */
	private List<String> getGlobalBundleDependencies(
			GlobalPostProcessingContext ctx, List<String> excludedBundles) {
		List<JoinableResourceBundle> globalBundles = getRsBundlesHandler(ctx)
				.getGlobalBundles();
		List<String> globalBundleDependencies = new ArrayList<String>();
		for (JoinableResourceBundle globalBundle : globalBundles) {
			if (!excludedBundles.contains(globalBundle.getName())) {
				globalBundleDependencies.add(globalBundle.getName());
			}
		}
		return globalBundleDependencies;
	}

	/**
	 * Initialize the closure argument from the Jawr config
	 * 
	 * @param args
	 *            the arguments
	 * @param config
	 *            the Jawr config
	 */
	private void initCompilerClosureArgumentsFromConfig(List<String> args,
			JawrConfig config) {
		Set<Entry<Object, Object>> entrySet = config.getConfigProperties()
				.entrySet();
		for (Entry<Object, Object> propEntry : entrySet) {
			String key = (String) propEntry.getKey();
			if (key.startsWith(JAWR_JS_CLOSURE_PREFIX)
					&& !JAWR_JS_CLOSURE_SPECIFIC_PROPERTIES.contains(key)) {

				String compilerArgName = key.substring(JAWR_JS_CLOSURE_PREFIX
						.length());
				checkCompilerArgumentName(compilerArgName);
				String compilerArgValue = (String) propEntry.getValue();
				compilerArgValue = getCompilerArgValue(compilerArgName,
						compilerArgValue);
				args.add(CLOSURE_ARGUMENT_NAME_PREFIX + compilerArgName);
				args.add(propEntry.getValue().toString());
			}
		}

		// Add default compilation level argument
		if (!args.contains(COMPILATION_LEVEL_ARG)) {
			args.add(COMPILATION_LEVEL_ARG);
			args.add(WHITESPACE_ONLY_COMPILATION_LEVEL);
		}

		// Add default level warning argument if not defined
		if (!args.contains(WARNING_LEVEL_ARG)) {
			args.add(WARNING_LEVEL_ARG);
			args.add(VERBOSE_WARNING_LEVEL);
		}
	}

	/**
	 * Initialize the modules arguments
	 * 
	 * @param resultBundlePathMapping
	 *            the map for the result bundle path
	 * @param args
	 *            the arguments
	 * @param bundles
	 *            the list of bundles
	 * @param bundleMap
	 *            the bundle map
	 * @param modules
	 *            the modules
	 * @param depModulesArgs
	 *            the dependency modules arguments
	 * @param globalBundleDependencies
	 *            the global bundle dependencies
	 */
	private void initModulesArgs(Map<String, String> resultBundlePathMapping,
			List<String> args, List<JoinableResourceBundle> bundles,
			Map<String, JoinableResourceBundle> bundleMap, String modules,
			List<String> depModulesArgs, List<String> globalBundleDependencies) {

		// Define Jawr root module
		// The JAWR_ROOT_MODULE is a fake module to give a root module to the
		// dependency graph
		// This is it's only purpose. It is the root dependency for any module
		// This is used because Google Closure use a unique module as root for
		// dependency management
		// in advance mode
		args.add(JS_ARG);
		args.add(JAWR_ROOT_MODULE_JS);

		args.add(MODULE_ARG);
		args.add(JAWR_ROOT_MODULE_NAME + ":1:");
		resultBundlePathMapping.put(JAWR_ROOT_MODULE_NAME, JAWR_ROOT_MODULE_JS);

		if (StringUtils.isNotEmpty(modules)) {
			String[] moduleSpecs = modules.split(";");
			for (String moduleSpec : moduleSpecs) {
				int moduleNameSeparatorIdx = moduleSpec.indexOf(":");
				if (moduleNameSeparatorIdx < 0) {
					throw new BundlingProcessException(
							"The property 'jawr.js.closure.modules' is not properly defined. Please check your configuration.");
				}

				// Check module name
				String bundleName = moduleSpec.substring(0,
						moduleNameSeparatorIdx);
				checkBundleName(bundleName, bundleMap);
				JoinableResourceBundle bundle = bundleMap.get(bundleName);
				List<String> dependencies = Arrays.asList(moduleSpec.substring(
						moduleNameSeparatorIdx + 1).split(MODULE_DEPENDENCIES_SEPARATOR));
				dependencies.addAll(0, globalBundleDependencies);
				generateBundleModuleArgs(depModulesArgs, bundleMap,
						resultBundlePathMapping, bundle, dependencies);

				// Remove the bundle from the list of bundle to treat
				bundles.remove(bundle);
			}
		}
	}

	/**
	 * Checks if the usage of the compiler argument name is allowed
	 * 
	 * @param compilerArgName
	 *            the compiler argument name
	 */
	private void checkCompilerArgumentName(String compilerArgName) {

		if (UNALLOWED_COMPILER_ARGS.contains(compilerArgName)) {
			throw new BundlingProcessException(
					"The usage of the closure argument \'" + compilerArgName
							+ "\' is not allowed.");
		}
	}

	/**
	 * Returns the compiler argument value
	 * 
	 * @param compilerArgName
	 *            the compiler argument name
	 * @param compilerArgValue
	 *            the compiler argument name
	 * @return the compiler argument value
	 */
	private String getCompilerArgValue(String compilerArgName,
			String compilerArgValue) {
		if (compilerArgName.equals(COMPILATION_LEVEL)) {
			if (!ADVANCED_OPTIMIZATIONS_COMPILATION_LEVEL
					.equalsIgnoreCase(compilerArgValue)
					&& !WHITESPACE_ONLY_COMPILATION_LEVEL
							.equalsIgnoreCase(compilerArgValue)
					&& !SIMPLE_OPTIMIZATIONS_COMPILATION_LEVEL
							.equalsIgnoreCase(compilerArgValue)) {

				if (StringUtils.isNotEmpty(compilerArgValue)) {
					LOGGER.debug("Closure compilation level defined in config '"
							+ compilerArgValue
							+ "' is not part of the available "
							+ "ones [WHITESPACE_ONLY, SIMPLE_OPTIMIZATIONS, ADVANCED_OPTIMIZATIONS");
				}
				compilerArgValue = WHITESPACE_ONLY_COMPILATION_LEVEL;
			}

			LOGGER.debug("Closure compilation level used : " + compilerArgValue);

		}
		return compilerArgValue;
	}

	/**
	 * Generates the bundle module arguments for the closure compiler
	 * 
	 * @param args
	 *            the current list of arguments
	 * @param bundleMap
	 *            the bundle map
	 * @param resultBundleMapping
	 *            the result bundle mapping
	 * @param bundle
	 *            the current bundle
	 * @param dependencies
	 *            the dependencies
	 */
	private void generateBundleModuleArgs(List<String> args,
			Map<String, JoinableResourceBundle> bundleMap,
			Map<String, String> resultBundleMapping,
			JoinableResourceBundle bundle, List<String> dependencies) {

		Set<String> bundleDependencies = getClosureModuleDependencies(bundle,
				dependencies);

		// Generate a module for each bundle variant
		Map<String, VariantSet> bundleVariants = bundle.getVariants();
		List<Map<String, String>> variants = VariantUtils
				.getAllVariants(bundleVariants);

		// Add default variant
		if (variants.isEmpty()) {
			variants.add(null);
		}

		for (Iterator<Map<String, String>> iterator = variants.iterator(); iterator
				.hasNext();) {
			Map<String, String> variant = iterator.next();

			String jsFile = VariantUtils.getVariantBundleName(bundle.getId(),
					variant, false);
			String moduleName = VariantUtils.getVariantBundleName(
					bundle.getName(), variant, false);

			resultBundleMapping.put(moduleName, jsFile);

			StringBuilder moduleArg = new StringBuilder();
			moduleArg.append(moduleName + ":1:");
			for (String dep : bundleDependencies) {

				// Check module dependencies
				checkBundleName(dep, bundleMap);

				JoinableResourceBundle dependencyBundle = bundleMap.get(dep);
				// Generate a module for each bundle variant
				List<String> depVariantKeys = VariantUtils
						.getAllVariantKeysFromFixedVariants(
								dependencyBundle.getVariants(), variant);

				for (Iterator<String> itDepVariantKey = depVariantKeys
						.iterator(); itDepVariantKey.hasNext();) {
					String depVariantKey = itDepVariantKey.next();
					String depBundleName = VariantUtils.getVariantBundleName(
							dep, depVariantKey, false);
					moduleArg.append(depBundleName);
					moduleArg.append(MODULE_DEPENDENCIES_SEPARATOR);
				}
			}
			moduleArg.append(JAWR_ROOT_MODULE_NAME);

			addModuleArg(jsFile, moduleName, args, moduleArg);
		}
	}

	/**
	 * Adds the module argument taking in account the module dependencies 
	 * @param jsFile the bundle js file
	 * @param moduleName the module name
	 * @param args the list of arguments to update
	 * @param moduleArg the module argument to add
	 */
	protected void addModuleArg(String jsFile, String moduleName,
			List<String> args, StringBuilder moduleArg) {
		int argIdx = 0;
		for (Iterator<String> iterArg = args.iterator(); iterArg.hasNext(); argIdx++) {
			String arg = iterArg.next();
			if(arg.equals(JS_ARG)){
				iterArg.next();
				arg = iterArg.next();
				argIdx += 2;
			}
			if(arg.equals(MODULE_ARG)){
				arg = iterArg.next();
				argIdx++;
				Matcher matcher = MODULE_ARG_PATTERN.matcher(arg);
				if(matcher.find()){
					String dep = matcher.group(1);
					if(dep != null){
						List<String> moduleDepdendencies = Arrays.asList(dep.split(MODULE_DEPENDENCIES_SEPARATOR));
						if(moduleDepdendencies.contains(moduleName)){
							break;
						}
					}
				}else{
					throw new BundlingProcessException(
							"There were an error in the generation of the module dependencies.");
				}
			}
		}
		
		args.add(argIdx++, JS_ARG);
		args.add(argIdx++, jsFile);
		args.add(argIdx++, MODULE_ARG);
		args.add(argIdx++, moduleArg.toString());
	}

	/**
	 * Returns the module bundle dependency from the bundle dependency and the
	 * declared dependencies
	 * 
	 * @param bundle
	 *            the bundle
	 * @param dependencies
	 *            the declared dependencies
	 * @return the list of the module dependency
	 */
	private Set<String> getClosureModuleDependencies(
			JoinableResourceBundle bundle, List<String> dependencies) {

		Set<String> bundleDependencies = new HashSet<String>();
		if (bundle.getDependencies() != null) {
			for (JoinableResourceBundle depBundle : bundle.getDependencies()) {
				bundleDependencies.add(depBundle.getName());
			}
		}
		
		for (String depBundleName : dependencies) {
			if(bundle.getInclusionPattern().isGlobal() && depBundleName.equals(bundle.getName())){
				break;
			}else{
				bundleDependencies.add(depBundleName);
			}
		}
		return bundleDependencies;
	}

	/**
	 * Checks the bundle name
	 * 
	 * @param bundleName
	 *            the bundle name
	 * @param bundleMap
	 *            the bundle map
	 */
	private void checkBundleName(String bundleName,
			Map<String, JoinableResourceBundle> bundleMap) {
		if (!JAWR_ROOT_MODULE_NAME.equals(bundleName)) {
			boolean moduleExist = bundleMap.get(bundleName) != null;
			if (!moduleExist) {
				throw new BundlingProcessException(
						"The bundle name '"
								+ bundleName
								+ "' defined in 'jawr.js.closure.modules' is not defined in the configuration. Please check your configuration.");
			}
		}
	}

	/**
	 * Returns the ResourcebundlesHandler
	 * 
	 * @param the
	 *            global processing context
	 * @return the ResourcebundlesHandler
	 */
	public ResourceBundlesHandler getRsBundlesHandler(
			GlobalPostProcessingContext ctx) {
		return ctx.getBundleHandler();
	}

	/**
	 * The Closure command line runner for Jawr
	 * 
	 * @author Ibrahim Chaehoi
	 */
	private class JawrClosureCommandLineRunner extends CommandLineRunner {

		/**
		 * The global postprocessing context
		 */
		private GlobalPostProcessingContext ctx;

		/**
		 * The result bundle mapping
		 */
		private Map<String, String> resultBundleMapping;

		/**
		 * Constructor
		 * 
		 * @param ctx
		 *            the global post processing context
		 * @param bundles
		 *            the bundles
		 * @param resultBundleMapping
		 *            the result bundle mapping
		 */
		public JawrClosureCommandLineRunner(GlobalPostProcessingContext ctx,
				List<JoinableResourceBundle> bundles,
				Map<String, String> resultBundleMapping) {
			super(getClosureCompilerArgs(ctx, bundles, resultBundleMapping));

			this.ctx = ctx;
			this.resultBundleMapping = resultBundleMapping;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see com.google.javascript.jscomp.CommandLineRunner#createCompiler()
		 */
		@Override
		protected Compiler createCompiler() {
			Compiler compiler = new Compiler(getErrorPrintStream());

			// Disable thread if needed
			if (Boolean.getBoolean(ctx.getJawrConfig().getProperty(
					JAWR_JS_CLOSURE_DISABLE_THREAD, "false"))) {
				compiler.disableThreads();
			}
			return compiler;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * com.google.javascript.jscomp.AbstractCommandLineRunner#checkModuleName
		 * (java.lang.String)
		 */
		@Override
		protected void checkModuleName(String name) throws FlagUsageException {

		}

		/**
		 * Creates inputs from a list of files.
		 * 
		 * @param files
		 *            A list of filenames
		 * @param allowStdIn
		 *            Whether '-' is allowed appear as a filename to represent
		 *            stdin. If true, '-' is only allowed to appear once.
		 * @return An array of inputs
		 * @throws
		 */
		@Override
		protected List<SourceFile> createInputs(List<String> files,
				boolean allowStdIn) throws IOException {

			List<SourceFile> inputs = new ArrayList<SourceFile>(files.size());

			for (String filename : files) {
				if (filename.equals(JAWR_ROOT_MODULE_JS)) {
					SourceFile newFile = SourceFile.fromCode(filename, "");
					inputs.add(newFile);
				} else if (!"-".equals(filename)) {
					Reader rd = null;
					StringWriter swr = new StringWriter();
					InputStream is = null;
					try {
						try {
							is = new FileInputStream(
									new File(tempDir, filename));
							rd = Channels.newReader(Channels.newChannel(is),
									ctx.getJawrConfig().getResourceCharset()
											.displayName());
						} catch (FileNotFoundException e) {
							// Do nothing
						}

						if (rd == null) {
							try {
								rd = ctx.getRsReaderHandler().getResource(
										filename);
							} catch (ResourceNotFoundException e1) {
								throw new BundlingProcessException(e1);
							}
						}

						String jsCode = CharStreams.toString(rd);
						SourceFile newFile = SourceFile.fromCode(filename,
								jsCode);
						inputs.add(newFile);
					} finally {
						IOUtils.close(is);
						IOUtils.close(rd);
						IOUtils.close(swr);
					}
				}
			}
			return inputs;
		}

		/**
		 * Converts a file name into a Writer. Returns null if the file name is
		 * null.
		 * 
		 * @throws IOException
		 */
		@Override
		protected OutputStream filenameToOutputStream(String fileName)
				throws IOException {

			if (fileName == null) {
				return null;
			}

			int fileExtensionIdx = fileName.lastIndexOf(".");
			String bundleName = fileName.substring(0, fileExtensionIdx)
					.substring(2);

			String bundlePath = resultBundleMapping.get(bundleName);
			bundlePath = PathNormalizer.escapeToPhysicalPath(bundlePath);
			File outFile = new File(destDir, bundlePath);
			outFile.getParentFile().mkdirs();
			FileOutputStream fos = new FileOutputStream(outFile);
			
			File outZipFile = new File(destZipDir, bundlePath);
			outZipFile.getParentFile().mkdirs();
			GZIPOutputStream gzOs = new GZIPOutputStream(new FileOutputStream(outZipFile));
			return new TeeOutputStream(fos, gzOs);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * com.google.javascript.jscomp.JawrAbstractCommandLineRunner#doRun()
		 */
		public int doRun() throws FlagUsageException, IOException {
			int result = super.doRun();
			// Delete JAWR_ROOT_MODULE file
			File jawrRootModuleFile = new File(destDir,
					resultBundleMapping.get(JAWR_ROOT_MODULE_NAME));
			if (!jawrRootModuleFile.delete()) {
				LOGGER.warn("Enable to delete JAWR_ROOT_MODULE.js file");
			}
			jawrRootModuleFile = new File(destZipDir,
					resultBundleMapping.get(JAWR_ROOT_MODULE_NAME));
			if (!jawrRootModuleFile.delete()) {
				LOGGER.warn("Enable to delete JAWR_ROOT_MODULE.js file");
			}
			return result;
		}
	}
}
