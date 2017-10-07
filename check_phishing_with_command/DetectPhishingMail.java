import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang.WordUtils;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.FileNotFoundException;
import java.io.BufferedReader;

import edu.stanford.nlp.ling.SentenceUtils;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalStructureFactory;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.trees.tregex.TregexMatcher;
import edu.stanford.nlp.trees.tregex.TregexPattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

public class DetectPhishingMail {

	private static String[] specialWord;

	/*
	 * Extracting phishing keywords
	 */
	private static void searchKeyword(List<TypedDependency> tdl, List<String> verb, List<String> obj) {
		for (int i = 0; i < tdl.size(); i++) {
			String typeDepen = tdl.get(i).reln().toString();

			// verb
			if (verb.contains(typeDepen)) {
				System.out.print("verb :" + tdl.get(i).dep().originalText() + ">");
				System.out.println(tdl.get(i).gov().originalText());
			}
			// obj
			if (obj.contains(typeDepen)) {
				System.out.print("obj :" + tdl.get(i).gov().originalText() + ">");
				System.out.println(tdl.get(i).dep().originalText());
			}
		}
	}

	/*
	 * Extracting command sentence
	 */
	// "(@VP=verb (< S !> SBAR) !$,,@NP)"
	// (@VP=verb (< S !> SBAR) | (< S & > S & < NN) !$,,@NP)
	private static boolean isImperative(Tree parse) {
		TregexPattern noNP = TregexPattern.compile("((@VP=verb > (S !> SBAR)) !$,,@NP)");
		TregexMatcher n = noNP.matcher(parse);
		while (n.find()) {
			String match = n.getMatch().firstChild().label().toString();

			// remove gerund, to + infinitiv
			if (match.equals("VP")) {
				match = n.getMatch().firstChild().firstChild().label().toString();
			}
			if (match.equals("TO") || match.equals("VBG")) {
				continue;
			}

			// imperative sentence
			System.out.println("It is imperative sentence.");
			return true;
		}
		return false;
	}

	private static String extractOneWord(int num, ArrayList<TaggedWord> listedTaggedString) {
		return listedTaggedString.get(num).toString().toLowerCase();
	}

	private static boolean isSuggestion(LexicalizedParser lp, String sentence,
			ArrayList<TaggedWord> listedTaggedString) {
		//
		for (int i = 0; i < listedTaggedString.size() - 1; i++) {
			if (extractOneWord(i, listedTaggedString).contentEquals("should/md")
					|| extractOneWord(i, listedTaggedString).contentEquals("could/md")
					|| extractOneWord(i, listedTaggedString).contentEquals("might/md")
					|| extractOneWord(i, listedTaggedString).contentEquals("may/md")
					|| extractOneWord(i, listedTaggedString).contentEquals("must/md")
					|| (extractOneWord(i, listedTaggedString).contentEquals("have/vbp")
							&& extractOneWord(i + 1, listedTaggedString).contentEquals("to/to"))) {
				if (i != 0 && extractOneWord(i - 1, listedTaggedString).contentEquals("you/prp")) {
					System.out.println("It is suggestion.");
					return true;
				}
			} else if (extractOneWord(i, listedTaggedString).contentEquals("would/md")) {
				if (extractOneWord(i + 1, listedTaggedString).contentEquals("like/vb")) {
					System.out.println("It is desire.");
					return true;
				} else if (i != 0 && extractOneWord(i - 1, listedTaggedString).contentEquals("you/prp")) {
					System.out.println("It is suggestion.");
					return true;
				}
			} else if (extractOneWord(i, listedTaggedString).contentEquals("'d/md")) {
				if (extractOneWord(i + 1, listedTaggedString).contentEquals("like/md")) {
					System.out.println("It is desire.");
					return true;
				} else if (i != 0 && extractOneWord(i - 1, listedTaggedString).contentEquals("you/prp")) {
					System.out.println("It is suggestion.");
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isSuggestion(Tree parse) {
		TregexPattern sug = TregexPattern.compile("((@VP=md > S ) $,,@NP=you )");
		TregexMatcher s = sug.matcher(parse);

		while (s.find()) {
			String y = s.getNode("you").getChild(0).getChild(0).value();

			if (y.equals("you") || y.equals("You") || y.equals("YOU")) {
				System.out.println("It is suggestion sentence.");
				return true;
			}
		}
		return false;
	}

	private static boolean isDesireExpression(List<TypedDependency> tdl) {
		for (int i = 0; i < tdl.size(); i++) {
			String extractElement = tdl.get(i).reln().toString();
			String oneWord = tdl.get(i).gov().value().toString().toLowerCase();
			if (extractElement.equals("nsubj")) {
				if (oneWord.contains("want") || oneWord.equals("hope") || oneWord.equals("wish")
						|| oneWord.equals("desire")) {
					System.out.println("It is desire sentence.");
					return true;
					// printObjVerb(tdl);
				}
			}
		}
		return false;
	}

	private static void detectCommand(LexicalizedParser lp, String sentence, PrintWriter pw2) throws IOException {

		// if the sentence has only one word, go to the next sentence.
		if (sentence.split(" ").length < 2) {
			return;
		}

		// penn tree
		TokenizerFactory<CoreLabel> tokenizerFactory = PTBTokenizer.factory(new CoreLabelTokenFactory(), "");
		Tokenizer<CoreLabel> tok = tokenizerFactory.getTokenizer(new StringReader(sentence));
		List<CoreLabel> rawWords = tok.tokenize();
		Tree parse = lp.apply(rawWords);
		ArrayList<TaggedWord> listedTaggedString = parse.taggedYield();

		// dependency
		TreebankLanguagePack tlp = lp.treebankLanguagePack(); // PennTreebankLanguagePack for English
		GrammaticalStructureFactory gsf = tlp.grammaticalStructureFactory();
		GrammaticalStructure gs = gsf.newGrammaticalStructure(parse);
		List<TypedDependency> tdl = gs.typedDependenciesCCprocessed();

		System.out.println("<<< " + sentence + " >>>");

		// 0. Hope, Kindly, Apply, Reply exception process
		for (int i = 0; i < 4; i++) {
			if (sentence.toLowerCase().startsWith(specialWord[i])) {
				System.out.println("It is imperative sentence.");
				searchKeyword(tdl, Arrays.asList("nmod", "nsubj", "subjpass"), Arrays.asList("dobj"));
				System.out.println();
				return;
			}
		}

		// 1. extracting imperative sentence
		if (isImperative(parse)) {
			searchKeyword(tdl, Arrays.asList("nmod", "nsubj", "subjpass"), Arrays.asList("dobj"));
			pw2.println(sentence);
		}

		// 2. extracting suggestion sentence
		else if (isSuggestion(lp, sentence, listedTaggedString)) {
			searchKeyword(tdl, Arrays.asList("nsubj", "subjpass"), Arrays.asList("dobj"));
			pw2.println(sentence);
		}

		// 3. extracting sentence including desire expression
		else if (isDesireExpression(tdl)) {
			// ���
			searchKeyword(tdl, Arrays.asList("nsubj", "subjpass"), Arrays.asList("dobj"));
			pw2.println(sentence);
		}
		System.out.println();
	}

	public static List<String> readArray(JsonReader reader) throws IOException {
		List<String> contents = new ArrayList<String>();

		reader.beginArray();
		while (reader.hasNext()) {
			contents.add(reader.nextString());
		}
		reader.endArray();
		return contents;
	}

	public static void main(String[] args) throws IOException {
		String parserModel = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
		String fileName = System.getProperty("user.dir") + "\\src\\data";

		LexicalizedParser lp = LexicalizedParser.loadModel(parserModel);

		PrintWriter pw2 = new PrintWriter(new FileWriter(
				"c:/users/dyson/desktop/java_workspace/stanfordParser/extract_imperative_command.txt", true));

		int count = 0;
		specialWord = new String[4];
		specialWord[0] = "hope";
		specialWord[1] = "reply";
		specialWord[2] = "apply";
		specialWord[3] = "kindly";

		if (args.length == 0) {
			Scanner scanner = null;
			try {
				scanner = new Scanner(System.in);
				System.out.println("Select the input method\n 1: text input 2: text File 3:JSON File  >> ");
				int inputMethod = scanner.nextInt();

				switch (inputMethod) {

				// standard text input
				case 1:
					while (scanner.hasNext()) {
						String value = scanner.nextLine();
						detectCommand(lp, value, pw2);
					}
					break;

				// text input file
				case 2:
					FileReader fr = null;
					BufferedReader br = null;
					try {
						fr = new FileReader("c:/users/dyson/desktop/java_workspace/stanfordParser/imperatives.txt");
						br = new BufferedReader(fr);

						String value;
						while ((value = br.readLine()) != null) {
							value = WordUtils.capitalizeFully(value, new char[] { '.' });
							// reply, hope,
							System.out.println(++count);
							detectCommand(lp, value, pw2);
						}
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						try {
							if (br != null)
								br.close();
							if (fr != null)
								fr.close();
						} catch (IOException ex) {
							ex.printStackTrace();
						}
					}
					break;

				// json input file
				case 3:

					try {
						JsonReader reader = new JsonReader(new FileReader(
								"c:/Users/dyson/Desktop/java_workspace/stanfordParser/sentence_tokenized_scam2.json"));
						Gson gson = new GsonBuilder().create();
						reader.beginObject();
						while (reader.hasNext()) {
							String name = reader.nextName();
							List<String> sentences = readArray(reader);
							for (String value : sentences) {
								value = WordUtils.capitalizeFully(value, new char[] { '.' });
								System.out.println(++count);
								detectCommand(lp, value, pw2);
							}
						}
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
					break;

				default:
					System.out.println("wrong input");
				}
			} finally {
				if (scanner != null)
					scanner.close();
			}
		}
		pw2.close();
	}

}
