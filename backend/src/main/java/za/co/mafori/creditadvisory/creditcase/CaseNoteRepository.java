package za.co.mafori.creditadvisory.creditcase;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseNoteRepository extends JpaRepository<CaseNote, Long> {

    /** All notes of one case, newest first, with their authors. */
    @EntityGraph(attributePaths = { "author", "author.consultant" })
    List<CaseNote> findByCreditCaseIdOrderByCreatedAtDescIdDesc(Long creditCaseId);
}
