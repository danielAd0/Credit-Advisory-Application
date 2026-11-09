package za.co.mafori.creditadvisory.imports.excel;

import java.util.List;
import java.util.Optional;

/** A workbook already read into memory and closed. Sheets are in workbook order. */
public interface RawWorkbook {

    List<RawSheet> sheets();

    default Optional<RawSheet> sheet(String name) {
        return sheets().stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
