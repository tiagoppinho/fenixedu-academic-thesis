package org.fenixedu.academic.thesis.ui.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.fenixedu.academic.domain.Degree;
import org.fenixedu.academic.domain.ExecutionDegree;
import org.fenixedu.academic.domain.ExecutionYear;
import org.fenixedu.academic.domain.accessControl.CoordinatorGroup;
import org.fenixedu.academic.domain.exceptions.DomainException;
import org.fenixedu.academic.domain.student.Registration;
import org.fenixedu.academic.thesis.domain.StudentThesisCandidacy;
import org.fenixedu.academic.thesis.domain.ThesisProposal;
import org.fenixedu.academic.thesis.domain.ThesisProposalParticipant;
import org.fenixedu.academic.thesis.domain.ThesisProposalParticipantType;
import org.fenixedu.academic.thesis.domain.ThesisProposalsConfiguration;
import org.fenixedu.academic.thesis.domain.ThesisProposalsSystem;
import org.fenixedu.academic.thesis.ui.bean.ThesisProposalBean;
import org.fenixedu.academic.thesis.ui.bean.ThesisProposalParticipantBean;
import org.fenixedu.academic.thesis.ui.exception.IllegalParticipantTypeException;
import org.fenixedu.academic.thesis.ui.exception.MaxNumberThesisProposalsException;
import org.fenixedu.academic.thesis.ui.exception.OutOfProposalPeriodException;
import org.fenixedu.academic.thesis.ui.exception.ThesisProposalException;
import org.fenixedu.academic.thesis.ui.exception.UnequivalentThesisConfigurationsException;
import org.fenixedu.academic.thesis.ui.exception.UnexistentThesisParticipantException;
import org.fenixedu.bennu.core.domain.User;
import org.fenixedu.bennu.core.groups.DynamicGroup;
import org.fenixedu.bennu.signals.DomainObjectEvent;
import org.fenixedu.bennu.signals.Signal;
import org.springframework.stereotype.Service;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.Atomic.TxMode;
import pt.ist.fenixframework.FenixFramework;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

@Service
public class ThesisProposalsService {

    public Map<Degree, List<ThesisProposal>> getCoordinatorProposals(User user, ExecutionYear year) {
        return getCoordinatorProposals(user, year, null, null, null);
    }

    public Map<Degree, List<ThesisProposal>> getCoordinatorProposals(User user, ExecutionYear year, Boolean isVisible,
            Boolean isAttributed, Boolean hasCandidacy) {
        Map<Degree, List<ThesisProposal>> coordinatorProposals = new HashMap<Degree, List<ThesisProposal>>();

        for (Degree degree : Degree.readBolonhaDegrees()) {

            Stream<ThesisProposal> proposalsStream = getThesisProposals(user, year, degree);

            if (isVisible != null) {
                Predicate<ThesisProposal> visiblePredicate = proposal -> isVisible.equals(!proposal.getHidden());
                proposalsStream = proposalsStream.filter(visiblePredicate);
            }

            if (hasCandidacy != null) {
                Predicate<ThesisProposal> hasCandidacyPredicate =
                        proposal -> hasCandidacy ? proposal.getStudentThesisCandidacySet().size() > 0 : proposal
                                .getStudentThesisCandidacySet().size() == 0;

                proposalsStream = proposalsStream.filter(hasCandidacyPredicate);
            }

            if (isAttributed != null) {
                Predicate<StudentThesisCandidacy> attributedPredicate =
                        isAttributed ? StudentThesisCandidacy::getAcceptedByAdvisor : candidacy -> !candidacy
                                .getAcceptedByAdvisor();

                proposalsStream =
                        proposalsStream.filter(p -> p.getStudentThesisCandidacySet().isEmpty() ? !isAttributed : p
                                .getStudentThesisCandidacySet().stream().anyMatch(attributedPredicate));
            }

            List<ThesisProposal> proposals = proposalsStream.collect(Collectors.toList());

            if (!proposals.isEmpty()) {
                coordinatorProposals.put(degree, proposals);
            }
        }

        return coordinatorProposals;
    }

    public Stream<ThesisProposal> getThesisProposals(User user, ExecutionYear year, Degree degree) {
        return degree.getExecutionDegrees().stream()
                .filter(executionDegree -> CoordinatorGroup.get(executionDegree.getDegree()).isMember(user))
                .filter(executionDegree -> executionDegree.getExecutionYear().isAfterOrEquals(year))
                .flatMap(executionDegree -> executionDegree.getThesisProposalsConfigurationSet().stream())
                .flatMap(config -> config.getThesisProposalSet().stream())
                .sorted(ThesisProposal.COMPARATOR_BY_NUMBER_OF_CANDIDACIES);
    }

    public List<ThesisProposal> getThesisProposals(User user, ThesisProposalsConfiguration configuration) {
        return ThesisProposal.readProposalsByUserAndConfiguration(user, configuration).stream()
                .sorted(ThesisProposal.COMPARATOR_BY_NUMBER_OF_CANDIDACIES).collect(Collectors.toList());
    }

    public List<ThesisProposalsConfiguration> getThesisProposalsConfigurations(User user) {
        return user.getThesisProposalParticipantSet().stream().map(participant -> participant.getThesisProposal())
                .flatMap(proposal -> proposal.getThesisConfigurationSet().stream())
                .sorted(ThesisProposalsConfiguration.COMPARATOR_BY_PROPOSAL_PERIOD_START_DESC).distinct()
                .collect(Collectors.toList());
    }

    public Set<ThesisProposalsConfiguration> getNotPastExecutionDegrees(User user, ExecutionYear year) {
        Set<ExecutionDegree> notPastExecDegrees =
                user.getPerson().getTeacher().getProfessorships().stream()
                        .flatMap(professorship -> professorship.getExecutionCourse().getExecutionDegrees().stream())
                        .map(execDegree -> execDegree.getDegree()).flatMap(degree -> degree.getExecutionDegrees().stream())
                        .filter(executionDegree -> executionDegree.getExecutionYear().isAfterOrEquals(year))
                        .collect(Collectors.toSet());

        HashMap<Degree, Set<ThesisProposalsConfiguration>> map = new HashMap<Degree, Set<ThesisProposalsConfiguration>>();

        notPastExecDegrees.stream().flatMap(execDegree -> execDegree.getThesisProposalsConfigurationSet().stream())
                .filter(config -> config.getProposalPeriod().getEnd().isAfterNow()).forEach(config -> {
                    Degree degree = config.getExecutionDegree().getDegree();
                    if (!map.containsKey(degree)) {
                        map.put(degree, new HashSet<ThesisProposalsConfiguration>());
                    }
                    map.get(degree).add(config);
                });

        Set<ThesisProposalsConfiguration> suggestedConfigs = new HashSet<ThesisProposalsConfiguration>();
        for (Degree degree : map.keySet()) {
            Optional<ThesisProposalsConfiguration> config =
                    map.get(degree).stream().min(ThesisProposalsConfiguration.COMPARATOR_BY_PROPOSAL_PERIOD_START_ASC);
            if (config.isPresent()) {
                suggestedConfigs.add(config.get());
            }
        }
        return suggestedConfigs;
    }

    @Atomic(mode = TxMode.WRITE)
    public ThesisProposal createThesisProposal(ThesisProposalBean proposalBean, String participantsJson)
            throws ThesisProposalException {

        JsonParser parser = new JsonParser();
        JsonArray jsonArray = parser.parse(participantsJson).getAsJsonArray();

        Set<ThesisProposalParticipantBean> participants = new HashSet<ThesisProposalParticipantBean>();

        for (JsonElement elem : jsonArray) {
            String userId = elem.getAsJsonObject().get("userId").getAsString();
            String userType = elem.getAsJsonObject().get("userType").getAsString();

            if (userType.isEmpty()) {
                throw new IllegalParticipantTypeException(User.findByUsername(userId));
            }

            if (userId == null || userId.isEmpty()) {
                throw new UnexistentThesisParticipantException();
            }

            participants.add(new ThesisProposalParticipantBean(User.findByUsername(userId), userType));
        }

        if (participants.isEmpty()) {
            throw new UnexistentThesisParticipantException();
        }

        proposalBean.setThesisProposalParticipantsBean(participants);
        ThesisProposal thesisProposal = new ThesisProposalBean.Builder(proposalBean).build();
        Signal.emit(ThesisProposal.SIGNAL_CREATED, new DomainObjectEvent<ThesisProposal>(thesisProposal));
        return thesisProposal;
    }

    @Atomic(mode = TxMode.WRITE)
    public boolean delete(ThesisProposal thesisProposal) {
        try {
            thesisProposal.delete();
        } catch (DomainException domainException) {
            return false;
        }
        return true;
    }

    public Map<String, StudentThesisCandidacy> getBestAccepted(ThesisProposal thesisProposal) {
        HashMap<String, StudentThesisCandidacy> bestAccepted = new HashMap<String, StudentThesisCandidacy>();

        for (StudentThesisCandidacy candidacy : thesisProposal.getStudentThesisCandidacySet()) {
            Registration registration = candidacy.getRegistration();
            if (!bestAccepted.containsKey(registration.getExternalId())) {
                for (StudentThesisCandidacy studentCandidacy : registration.getStudentThesisCandidacySet()) {
                    if (studentCandidacy.getAcceptedByAdvisor()
                            && (!bestAccepted.containsKey(registration.getExternalId()) || studentCandidacy.getPreferenceNumber() < bestAccepted
                                    .get(registration.getExternalId()).getPreferenceNumber())) {
                        bestAccepted.put(registration.getExternalId(), studentCandidacy);
                    }
                }
            }
        }
        return bestAccepted;
    }

    @Atomic(mode = TxMode.WRITE)
    public void editThesisProposal(User currentUser, ThesisProposalBean thesisProposalBean, ThesisProposal thesisProposal,
            JsonArray jsonArray) throws ThesisProposalException {

        boolean isManager = DynamicGroup.get("managers").isMember(currentUser);
        boolean isDegreeCoordinator =
                thesisProposal.getExecutionDegreeSet().stream()
                        .anyMatch(execDegree -> CoordinatorGroup.get(execDegree.getDegree()).isMember(currentUser));

        ArrayList<ThesisProposalParticipantBean> participantsBean = new ArrayList<ThesisProposalParticipantBean>();

        for (JsonElement elem : jsonArray) {
            String userId = elem.getAsJsonObject().get("userId").getAsString();
            String userType = elem.getAsJsonObject().get("userType").getAsString();

            if (userType.isEmpty()) {
                throw new IllegalParticipantTypeException(User.findByUsername(userId));
            }

            ThesisProposalParticipantBean participantBean =
                    new ThesisProposalParticipantBean(User.findByUsername(userId), userType);

            participantsBean.add(participantBean);
        }

        if (participantsBean.isEmpty()) {
            throw new UnexistentThesisParticipantException();
        }

        for (ThesisProposalParticipant participant : thesisProposal.getThesisProposalParticipantSet()) {
            participant.delete();
        }

        thesisProposal.getThesisProposalParticipantSet().clear();

        ArrayList<ThesisProposalParticipant> participants = new ArrayList<ThesisProposalParticipant>();

        for (ThesisProposalParticipantBean participantBean : participantsBean) {
            User user = FenixFramework.getDomainObject(participantBean.getUserExternalId());

            ThesisProposalParticipantType participantType =
                    FenixFramework.getDomainObject(participantBean.getParticipantTypeExternalId());

            ThesisProposalParticipant participant = new ThesisProposalParticipant(user, participantType);

            for (ThesisProposalsConfiguration configuration : thesisProposal.getThesisConfigurationSet()) {
                int proposalsCount =
                        configuration
                                .getThesisProposalSet()
                                .stream()
                                .filter(proposal -> proposal.getThesisProposalParticipantSet().stream().map(p -> p.getUser())
                                        .collect(Collectors.toSet()).contains(participant.getUser())).collect(Collectors.toSet())
                                .size();

                if (!(isManager || isDegreeCoordinator) && configuration.getMaxThesisProposalsByUser() != -1
                        && proposalsCount >= configuration.getMaxThesisProposalsByUser()) {
                    throw new MaxNumberThesisProposalsException(participant);
                }

                else {
                    participant.setThesisProposal(thesisProposal);
                    participants.add(participant);
                }
            }
        }

        thesisProposal.setTitle(thesisProposalBean.getTitle());
        thesisProposal.setObservations(thesisProposalBean.getObservations());
        thesisProposal.setRequirements(thesisProposalBean.getRequirements());
        thesisProposal.setGoals(thesisProposalBean.getGoals());
        thesisProposal.setHidden(thesisProposalBean.getHidden());
        thesisProposal.getThesisConfigurationSet().clear();
        thesisProposal.getThesisConfigurationSet().addAll(thesisProposalBean.getThesisProposalsConfigurations());

        thesisProposal.getThesisProposalParticipantSet().addAll(participants);

        ThesisProposalsConfiguration base =
                (ThesisProposalsConfiguration) thesisProposalBean.getThesisProposalsConfigurations().toArray()[0];

        for (ThesisProposalsConfiguration configuration : thesisProposalBean.getThesisProposalsConfigurations()) {
            if (!base.isEquivalent(configuration)) {
                throw new UnequivalentThesisConfigurationsException(base, configuration);
            }
        }

        ThesisProposalsConfiguration config = thesisProposal.getSingleThesisProposalsConfiguration();

        if (!(isManager || isDegreeCoordinator) && !config.getProposalPeriod().containsNow()) {
            throw new OutOfProposalPeriodException();
        }
        thesisProposal.setLocalization(thesisProposalBean.getLocalization());
    }

    @Atomic(mode = TxMode.WRITE)
    public void accept(StudentThesisCandidacy studentThesisCandidacy) {
        for (StudentThesisCandidacy candidacy : studentThesisCandidacy.getThesisProposal().getStudentThesisCandidacySet()) {
            candidacy.setAcceptedByAdvisor(false);
        }

        studentThesisCandidacy.setAcceptedByAdvisor(true);
    }

    @Atomic(mode = TxMode.WRITE)
    public void reject(StudentThesisCandidacy studentThesisCandidacy) {
        studentThesisCandidacy.setAcceptedByAdvisor(false);
    }

    public List<ThesisProposalParticipantType> getThesisProposalParticipantTypes() {
        return ThesisProposalsSystem.getInstance().getThesisProposalParticipantTypeSet().stream()
                .sorted(ThesisProposalParticipantType.COMPARATOR_BY_WEIGHT).distinct().collect(Collectors.toList());
    }

    public List<ThesisProposalsConfiguration> getCurrentThesisProposalsConfigurations() {
        return getCurrentThesisProposalsConfigurations(null);
    }

    public List<ThesisProposalsConfiguration> getCurrentThesisProposalsConfigurations(
            Comparator<ThesisProposalsConfiguration> comparator) {
        return getCurrentConfigurations(comparator).distinct().collect(Collectors.toList());
    }

    private Stream<ThesisProposalsConfiguration> getCurrentConfigurations(Comparator<ThesisProposalsConfiguration> comparator) {
        Stream<ThesisProposalsConfiguration> configurations =
                ThesisProposalsSystem.getInstance().getThesisProposalsConfigurationSet().stream()
                        .filter(config -> config.getProposalPeriod().containsNow());

        if (comparator != null) {
            configurations = configurations.sorted(comparator);
        }

        return configurations;

    }

    public List<StudentThesisCandidacy> getStudentThesisCandidacy(ThesisProposal thesisProposal) {
        return thesisProposal.getStudentThesisCandidacySet().stream().sorted(StudentThesisCandidacy.COMPARATOR_BY_DATETIME)
                .distinct().collect(Collectors.toList());

    }

    public Set<ThesisProposal> getRecentProposals(User user) {
        Set<ThesisProposal> proposals =
                user.getThesisProposalParticipantSet().stream().map(participant -> participant.getThesisProposal())
                        .collect(Collectors.toSet());

        HashMap<String, Set<ThesisProposal>> proposalTitleMap = new HashMap<String, Set<ThesisProposal>>();

        for (ThesisProposal proposal : proposals) {
            if (!proposalTitleMap.containsKey(proposal.getTitle())) {
                proposalTitleMap.put(proposal.getTitle(), new HashSet<ThesisProposal>());
            }
            proposalTitleMap.get(proposal.getTitle()).add(proposal);
        }

        Set<ThesisProposal> recentProposals = new HashSet<ThesisProposal>();
        for (String key : proposalTitleMap.keySet()) {
            recentProposals.add(proposalTitleMap.get(key).stream().max(ThesisProposal.COMPARATOR_BY_PROPOSAL_PERIOD).get());
        }
        return recentProposals;
    }

    public Map<Registration, TreeSet<StudentThesisCandidacy>> getCoordinatorCandidacies(ThesisProposalsConfiguration configuration) {

        Map<Registration, TreeSet<StudentThesisCandidacy>> map = new HashMap<Registration, TreeSet<StudentThesisCandidacy>>();

        configuration
                .getThesisProposalSet()
                .stream()
                .flatMap(proposal -> proposal.getStudentThesisCandidacySet().stream())
                .collect(Collectors.toSet())
                .forEach(
                        candidacy -> {
                            if (!map.containsKey(candidacy.getRegistration())) {
                                map.put(candidacy.getRegistration(), new TreeSet<StudentThesisCandidacy>(
                                        StudentThesisCandidacy.COMPARATOR_BY_PREFERENCE_NUMBER));
                            }
                            map.get(candidacy.getRegistration()).add(candidacy);
                        });
        return map;
    }

    @Atomic(mode = TxMode.WRITE)
    public void delete(StudentThesisCandidacy studentThesisCandidacy) {
        studentThesisCandidacy.delete();
    }
}
