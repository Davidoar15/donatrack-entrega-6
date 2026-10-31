package ar.edu.utn.dds.k3003.services;

import ar.edu.utn.dds.k3003.repositories.AsignacionRepository;
import ar.edu.utn.dds.k3003.messaging.ReadModelEventPublisher;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import ar.edu.utn.dds.k3003.repositories.DepositoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDbService {

  private final DepositoRepository depositoRepository;
  private final AsignacionRepository asignacionRepository;

  @Autowired(required = false)
  private ReadModelEventPublisher readModelEventPublisher;

  public AdminDbService(DepositoRepository depositoRepository, AsignacionRepository asignacionRepository) {
    this.depositoRepository = depositoRepository;
    this.asignacionRepository = asignacionRepository;
  }

  public record DbStatusResponse(long depositos, long paquetes, long asignaciones) {}

  public DbStatusResponse status() {
    long depositos = depositoRepository.count();
    long paquetes = depositoRepository.findAll().stream().mapToLong(d -> d.getStockActual().size()).sum();
    long asignaciones = asignacionRepository.count();

    return new DbStatusResponse(depositos, paquetes, asignaciones);
  }

  @Transactional
  public void clear() {
    if (readModelEventPublisher != null) {
      depositoRepository.findAll().forEach(deposito -> {
        deposito.getStockActual().forEach(paquete ->
            readModelEventPublisher.publicar("paquete", paquete.getId(), "DELETE", Map.of()));
        readModelEventPublisher.publicar("deposito", deposito.getId(), "DELETE", Map.of());
      });
      asignacionRepository.findAll().forEach(asignacion ->
          readModelEventPublisher.publicar("asignacion", asignacion.getId(), "DELETE", Map.of()));
    }
    asignacionRepository.deleteAll();
    depositoRepository.deleteAll();
  }
}
