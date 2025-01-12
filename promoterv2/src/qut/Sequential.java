package qut;

import qut.*;
import jaligner.*;
import jaligner.matrix.*;
import edu.au.jacobi.pattern.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Sequential {
    protected static HashMap<String, Sigma70Consensus> consensus = new HashMap<String, Sigma70Consensus>();
    private static Series sigma70_pattern = Sigma70Definition.getSeriesAll_Unanchored(0.7);
    private static final Matrix BLOSUM_62 = BLOSUM62.Load();
    private static byte[] complement = new byte['z'];

    static {
        complement['C'] = 'G'; complement['c'] = 'g';
        complement['G'] = 'C'; complement['g'] = 'c';
        complement['T'] = 'A'; complement['t'] = 'a';
        complement['A'] = 'T'; complement['a'] = 't';
    }

                    
    protected static List<Gene> ParseReferenceGenes(String referenceFile) throws FileNotFoundException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(referenceFile)));
        List<Gene> referenceGenes = new ArrayList<Gene>();
        while (true) {
            String name = reader.readLine();
            if (name == null)
                break;
            String sequence = reader.readLine();
            referenceGenes.add(new Gene(name, 0, 0, sequence));
            consensus.put(name, new Sigma70Consensus());
        }
        consensus.put("all", new Sigma70Consensus());
        reader.close();
        return referenceGenes;
    }

    protected static boolean Homologous(PeptideSequence A, PeptideSequence B) {
        return SmithWatermanGotoh.align(new Sequence(A.toString()), new Sequence(B.toString()), BLOSUM_62, 10f, 0.5f).calculateScore() >= 60;
    }

    protected static NucleotideSequence GetUpstreamRegion(NucleotideSequence dna, Gene gene) {
        int upStreamDistance = 250;
        if (gene.location < upStreamDistance)
           upStreamDistance = gene.location-1;

        if (gene.strand == 1)
            return new NucleotideSequence(java.util.Arrays.copyOfRange(dna.bytes, gene.location-upStreamDistance - 1, gene.location - 1));
        else {
            byte[] result = new byte[upStreamDistance];
            int reverseStart = dna.bytes.length - gene.location + upStreamDistance;
            for (int i = 0; i < upStreamDistance; i++)
                result[i] = complement[dna.bytes[reverseStart - i]];
            return new NucleotideSequence(result);
        }
    }

    protected static Match PredictPromoter(NucleotideSequence upStreamRegion) {
        return BioPatterns.getBestMatch(sigma70_pattern, upStreamRegion.toString());
    }

    protected static GenbankRecord Parse(String file) throws IOException {
        GenbankRecord record = new GenbankRecord();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        record.Parse(reader);
        reader.close();
        return record;
    }

    public static void run(String referenceFile, String dir) throws FileNotFoundException, IOException {             
        List<Gene> referenceGenes = ParseReferenceGenes(referenceFile);
        List<String> genbankFiles = ListGenbankFiles(dir);

        GeneProcessor processor = new GeneProcessor(referenceGenes);
        processor.processGeneFiles(genbankFiles);

        for (Map.Entry<String, Sigma70Consensus> entry : consensus.entrySet())
           System.out.println(entry.getKey() + " " + entry.getValue());
    }

    private static List<String> ListGenbankFiles(String dir) {
        List<String> list = new ArrayList<String>();
        ProcessDir(list, new File(dir));
        return list;
    }

    private static void ProcessDir(List<String> list, File dir) {
        if (dir.exists()) {
            for (File file : dir.listFiles()) {
                if (file.isDirectory()) {
                    ProcessDir(list, file);
                } else {
                    list.add(file.getPath());
                }
            }
        }
    }

    public static void main(String[] args) throws FileNotFoundException, IOException {
        // Record the start time
        long startTime = System.nanoTime();

        run("referenceGenes.list", "Ecoli");

        // Record the end time
        long endTime = System.nanoTime();
        // Calculate the elapsed time in seconds
        double elapsedTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;

        System.out.printf("Computation time: %.2f seconds%n", elapsedTimeInSeconds);
    }
}

class GeneProcessor {
    private final List<Gene> referenceGenes;

    public GeneProcessor(List<Gene> referenceGenes) {
        this.referenceGenes = referenceGenes;
    }

    public void processGeneFiles(List<String> geneFiles) {
        int numberOfCores = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfCores);

        for (String geneFile : geneFiles) {
            executorService.submit(() -> {
                try {
                    processGeneFile(geneFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("All files processed.");
    }

    private void processGeneFile(String geneFile) throws IOException {
        GenbankRecord record = Sequential.Parse(geneFile);
        for (Gene referenceGene : referenceGenes) {
            System.out.println(referenceGene.name);
            for (Gene gene : record.genes) {
                if (Sequential.Homologous(gene.sequence, referenceGene.sequence)) {
                    NucleotideSequence upStreamRegion = Sequential.GetUpstreamRegion(record.nucleotides, gene);
                    Match prediction = Sequential.PredictPromoter(upStreamRegion);
                    if (prediction != null) {
                        Sequential.consensus.get(referenceGene.name).addMatch(prediction);
                        Sequential.consensus.get("all").addMatch(prediction);
                    }
                }
            }
        }
    }
}
