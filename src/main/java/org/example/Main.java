package org.example;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Main {
    public static void main(String[] args) {
        try {
            Options opts = Options.parse(args);
            if (opts.inputFiles.isEmpty()) {
                System.err.println("Нет входных файлов. Использование: java -jar uxl.jar [опции] files...");
                Options.printHelp();
                return;
            }

            Processor processor = new Processor(opts);
            processor.run();
            processor.printStats();
        } catch (Options.HelpRequested e) {
            Options.printHelp();
        } catch (Exception e) {
            System.err.println("Критическая ошибка: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    // Опции командной строки
    static class Options {
        boolean append = false;         // -a
        boolean shortStats = false;     // -s
        boolean fullStats = false;      // -f
        String outDir = ".";           // -o
        String prefix = "";            // -p
        List<String> inputFiles = new ArrayList<>();

        static class HelpRequested extends RuntimeException {}

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        throw new HelpRequested();
                    case "-a":
                        o.append = true; break;
                    case "-s":
                        o.shortStats = true; break;
                    case "-f":
                        o.fullStats = true; break;
                    case "-o":
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Ожидается путь после -o");
                        o.outDir = args[++i];
                        break;
                    case "-p":
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Ожидается префикс после -p");
                        o.prefix = args[++i];
                        break;
                    default:
                        // всё остальное считаем файлами
                        o.inputFiles.add(a);
                }
            }

            if (!o.shortStats && !o.fullStats) {
                // По умолчанию краткая статистика
                o.shortStats = true;
            }
            if (o.shortStats && o.fullStats) {
                // Если обе выбраны — приоритет полной
                o.shortStats = false;
            }
            return o;
        }

        static void printHelp() {
            System.out.println("""
                    \nУтилита фильтрации содержимого файлов.\n\n""" +
                    "Использование:\n" +
                    "  java -jar uXl.jar [опции] in1.txt in2.txt ...\n\n" +
                    "Опции:\n" +
                    "  -o <path>     Путь для результатов (по умолчанию текущая папка)\n" +
                    "  -p <prefix>   Префикс имен файлов (по умолчанию без префикса)\n" +
                    "  -a            Режим добавления (append) вместо перезаписи\n" +
                    "  -s            Краткая статистика (count)\n" +
                    "  -f            Полная статистика (числа: min/max/sum/avg; строки: minLen/maxLen)\n" +
                    "  -h, --help    Справка\n\n" +
                    "Файлы результатов:\n" +
                    "  <prefix>integers.txt, <prefix>floats.txt, <prefix>strings.txt в <path>\n" +
                    "Строки читаются по очереди из всех входных файлов. Пустые строки пропускаются.\n");
        }
    }

    // Главный процесс
    static class Processor {
        private final Options opts;
        private final RoundRobinReader rr;

        private final LazyWriter intWriter;
        private final LazyWriter floatWriter;
        private final LazyWriter stringWriter;

        private final IntStats intStats = new IntStats();
        private final FloatStats floatStats = new FloatStats();
        private final StringStats stringStats = new StringStats();

        Processor(Options opts) throws IOException {
            this.opts = opts;
            this.rr = new RoundRobinReader(opts.inputFiles);

            Path outDirPath = Paths.get(opts.outDir);
            if (!Files.exists(outDirPath)) {
                try {
                    Files.createDirectories(outDirPath);
                } catch (IOException e) {
                    throw new IOException("Не удалось создать каталог вывода: " + outDirPath + ". " + e.getMessage(), e);
                }
            }

            this.intWriter = new LazyWriter(outDirPath.resolve(opts.prefix + "integers.txt"), opts.append);
            this.floatWriter = new LazyWriter(outDirPath.resolve(opts.prefix + "floats.txt"), opts.append);
            this.stringWriter = new LazyWriter(outDirPath.resolve(opts.prefix + "strings.txt"), opts.append);
        }

        void run() {
            String line;
            while ((line = rr.nextLine()) != null) {
                if (line.isEmpty()) continue; // считаем пустую строку отсутствием данных
                try {
                    Classification c = classify(line);
                    switch (c) {
                        case INTEGER -> {
                            intWriter.writeLine(line);
                            intStats.accept(new BigInteger(line));
                        }
                        case FLOAT -> {
                            floatWriter.writeLine(line);
                            floatStats.accept(new BigDecimal(normalizeFloat(line)));
                        }
                        case STRING -> {
                            stringWriter.writeLine(line);
                            stringStats.accept(line);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Предупреждение: строка пропущена: '" + line + "' — " + e.getMessage());
                }
            }

            // Закрыть при наличии
            try { intWriter.closeIfOpened(); } catch (IOException e) { System.err.println(e.getMessage()); }
            try { floatWriter.closeIfOpened(); } catch (IOException e) { System.err.println(e.getMessage()); }
            try { stringWriter.closeIfOpened(); } catch (IOException e) { System.err.println(e.getMessage()); }

            rr.close();
        }

        void printStats() {
            if (opts.fullStats) {
                System.out.println("== Полная статистика ==");
                System.out.println(intStats.full("Целые"));
                System.out.println(floatStats.full("Вещественные"));
                System.out.println(stringStats.full("Строки"));
            } else {
                System.out.println("== Краткая статистика ==");
                System.out.println(intStats.shortForm("Целые"));
                System.out.println(floatStats.shortForm("Вещественные"));
                System.out.println(stringStats.shortForm("Строки"));
            }
        }

        private Classification classify(String s) {
            // Сначала пробуем integer: допускаем ведущий +/-, десятичную запись без пробелов
            if (isInteger(s)) return Classification.INTEGER;
            if (isFloat(s)) return Classification.FLOAT;
            return Classification.STRING;
        }

        private boolean isInteger(String s) {
            // BigInteger парсит только целое без пробелов, допускаем +/-, десятичная система
            try {
                if (s.contains(".") || s.contains("e") || s.contains("E")) return false;
                new BigInteger(s);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        private boolean isFloat(String s) {
            // Признаки: содержит точку или экспоненту (E/e). Парсим BigDecimal.
            if (!(s.contains(".") || s.contains("e") || s.contains("E"))) return false;
            try {
                new BigDecimal(normalizeFloat(s));
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        private String normalizeFloat(String s) {
            return s.trim();
        }
    }

    enum Classification { INTEGER, FLOAT, STRING }

    // Пишет файл лениво: создаётся только при первом writeLine()
    static class LazyWriter {
        private final Path path;
        private final boolean append;
        private BufferedWriter writer;
        private boolean created = false;

        LazyWriter(Path path, boolean append) {
            this.path = path;
            this.append = append;
        }

        void writeLine(String line) throws IOException {
            if (writer == null) {
                // создать только при первом обращении
                OutputStream os = Files.newOutputStream(path, append ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND} : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE});
                writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                created = true;
            }
            writer.write(line);
            writer.newLine();
        }

        void closeIfOpened() throws IOException {
            if (writer != null) writer.close();
        }

        public boolean isCreated() { return created; }
    }

    // Чтение строк по очереди из нескольких файлов
    static class RoundRobinReader implements Closeable {
        private final List<BufferedReader> readers = new ArrayList<>();
        private final List<Boolean> finished = new ArrayList<>();
        private int index = 0;
        private int remaining;

        RoundRobinReader(List<String> files) throws IOException {
            if (files.isEmpty()) throw new IllegalArgumentException("Список файлов пуст");
            for (String f : files) {
                try {
                    BufferedReader br = Files.newBufferedReader(Paths.get(f), StandardCharsets.UTF_8);
                    readers.add(br);
                    finished.add(false);
                } catch (IOException e) {
                    System.err.println("Ошибка открытия файла '" + f + "': " + e.getMessage());
                    readers.add(null);
                    finished.add(true);
                }
            }
            remaining = (int) finished.stream().filter(b -> !b).count();
            if (remaining == 0) throw new IOException("Не удалось открыть ни один входной файл");
        }

        // Возвращает следующую строку по кругу, либо null когда все закончились
        String nextLine() {
            if (remaining == 0) return null;
            for (int attempts = 0; attempts < readers.size(); attempts++) {
                int i = (index + attempts) % readers.size();
                if (finished.get(i)) continue;
                BufferedReader br = readers.get(i);
                try {
                    String line = br.readLine();
                    if (line != null) {
                        index = (i + 1) % readers.size();
                        return line;
                    } else {
                        finished.set(i, true);
                        remaining--;
                        if (remaining == 0) return null;
                    }
                } catch (IOException e) {
                    System.err.println("Ошибка чтения: " + e.getMessage());
                    finished.set(i, true);
                    remaining--;
                    if (remaining == 0) return null;
                }
            }
            return nextLine();
        }

        @Override
        public void close() {
            for (BufferedReader br : readers) {
                if (br != null) try { br.close(); } catch (IOException ignored) {}
            }
        }
    }

    // Статистика для целых чисел
    static class IntStats {
        long count = 0;
        BigInteger min = null, max = null, sum = BigInteger.ZERO;

        void accept(BigInteger v) {
            count++;
            sum = sum.add(v);
            if (min == null || v.compareTo(min) < 0) min = v;
            if (max == null || v.compareTo(max) > 0) max = v;
        }

        String shortForm(String title) {
            return title + ": count=" + count;
        }

        String full(String title) {
            if (count == 0) return title + ": count=0";
            // average как BigDecimal с высокой точностью
            BigDecimal avg = new BigDecimal(sum).divide(new BigDecimal(count), MathContext.DECIMAL128);
            return String.format(Locale.ROOT, "%s: count=%d, min=%s, max=%s, sum=%s, avg=%s",
                    title, count, min.toString(), max.toString(), sum.toString(), avg.toPlainString());
        }
    }

    // Статистика для вещественных
    static class FloatStats {
        long count = 0;
        BigDecimal min = null, max = null, sum = BigDecimal.ZERO;
        private static final MathContext MC = MathContext.DECIMAL128;

        void accept(BigDecimal v) {
            count++;
            sum = sum.add(v, MC);
            if (min == null || v.compareTo(min) < 0) min = v;
            if (max == null || v.compareTo(max) > 0) max = v;
        }

        String shortForm(String title) {
            return title + ": count=" + count;
        }

        String full(String title) {
            if (count == 0) return title + ": count=0";
            BigDecimal avg = sum.divide(new BigDecimal(count), MC);
            return String.format(Locale.ROOT, "%s: count=%d, min=%s, max=%s, sum=%s, avg=%s",
                    title, count, min.toPlainString(), max.toPlainString(), sum.toPlainString(), avg.toPlainString());
        }
    }

    // Статистика для строк
    static class StringStats {
        long count = 0;
        Integer minLen = null, maxLen = null;

        void accept(String s) {
            count++;
            int len = s.length();
            if (minLen == null || len < minLen) minLen = len;
            if (maxLen == null || len > maxLen) maxLen = len;
        }

        String shortForm(String title) {
            return title + ": count=" + count;
        }

        String full(String title) {
            if (count == 0) return title + ": count=0";
            return String.format(Locale.ROOT, "%s: count=%d, minLen=%d, maxLen=%d",
                    title, count, minLen, maxLen);
        }
    }
}